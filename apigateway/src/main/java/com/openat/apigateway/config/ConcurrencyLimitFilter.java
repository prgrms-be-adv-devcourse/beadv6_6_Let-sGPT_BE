package com.openat.apigateway.config;

import com.openat.apigateway.error.ApiErrorResponseWriter;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * 동시에 "처리 중"인 요청 수가 상한을 넘으면 처리를 시작하지 않고 즉시 429로 거절한다.
 *
 * <p>MVC/Tomcat이 스레드 풀 상한(운영 기준 50개) 덕분에 부수 효과로 갖던 "동시 처리 요청 수
 * 제한"을, WebFlux/Netty 기반인 이 게이트웨이에서 명시적으로 재현한 것이다. 근거가 되는 실측
 * 결과와 기본값의 산정 근거는 {@link ConcurrencyLimitProperties} 자바독 참고 - 요약하면 운영과
 * 동일한 힙 상한(약 160MB)에서 동시접속 5,000~6,000명에 <b>3회 시행 전부 OOM으로 다운</b>됐고,
 * 이 필터 적용이 그 대응이다.
 *
 * <p><b>왜 "대기"가 아니라 "즉시 거절"인가</b>: MVC는 넘치는 요청을 OS 커널의 accept backlog에
 * 사실상 공짜로 대기시킬 수 있지만, 이벤트 루프에는 그런 공짜 대기 공간이 없다 - 대기시키려면
 * 그 대기 상태 자체가 또 힙을 쓰므로 문제를 미루기만 한다. 서비스 전체가 죽어 모든 사용자가
 * 못 쓰게 되는 것보다, 상한을 넘는 일부만 지연 없이 실패시켜 클라이언트의 재시도/폴백을 유도하는
 * 쪽이 낫다는 판단이다.
 *
 * <p>{@link Ordered#HIGHEST_PRECEDENCE}로 등록해 인증·라우팅보다 먼저 카운트를 확인한다 -
 * 그래야 상한 초과 시 뒤쪽 처리(본문 버퍼링, 다운스트림 프록시 등)로 인한 추가 메모리 사용
 * 자체를 막을 수 있다. {@code RouteExistenceFilter}도 같은 우선순위지만, 이 필터는 카운터만
 * 증감하고 통과시키므로 둘의 상대 순서는 동작에 영향이 없다.
 *
 * <p>액추에이터(k8s probe/Prometheus scrape) 경로는 상한에서 제외한다 - 과부하로 초과 요청을
 * 거절하는 상황에서 헬스체크까지 429가 되면 쿠버네티스가 정상 동작 중인 파드를 죽여버린다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConcurrencyLimitFilter implements WebFilter {

    private static final String ACTUATOR_PREFIX = "/actuator";

    private final int maxInFlight;
    private final ApiErrorResponseWriter responseWriter;
    private final AtomicInteger inFlight = new AtomicInteger(0);

    public ConcurrencyLimitFilter(ConcurrencyLimitProperties properties,
                                  ApiErrorResponseWriter responseWriter) {
        this.maxInFlight = properties.maxInFlightRequests();
        this.responseWriter = responseWriter;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith(ACTUATOR_PREFIX)) {
            return chain.filter(exchange);
        }

        int current = inFlight.incrementAndGet();
        if (current > maxInFlight) {
            inFlight.decrementAndGet();
            log.warn("[gateway-concurrency-limit] rejected - inFlight={} max={} path={}",
                    current - 1, maxInFlight, path);
            return responseWriter.write(
                    exchange,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "GATEWAY_CONCURRENCY_LIMIT_EXCEEDED",
                    "서버가 처리할 수 있는 동시 요청 수를 초과했습니다. 잠시 후 다시 시도해 주세요.");
        }
        return chain.filter(exchange).doFinally(signal -> inFlight.decrementAndGet());
    }
}
