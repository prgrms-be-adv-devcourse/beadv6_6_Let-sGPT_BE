package com.openat.apigateway.config;

import com.openat.apigateway.error.ApiErrorResponseWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
 *
 * <p>카운터를 SSE({@code /status/stream})와 일반 API로 분리한다(리뷰 지적으로 추가): SSE는
 * 연결 수명 내내 슬롯을 점유하는 성격이라 일반 제어 API(진입/폴링/GIVE_UP 등 decision)와 같은
 * 카운터를 공유하면, SSE 구독자가 상한을 다 채운 순간부터는 GIVE_UP 같은 상태 변경 요청도
 * 429로 거절돼 사용자가 스스로 자리를 반납할 방법조차 없어진다. 별도 카운터로 나눠 SSE 포화가
 * 제어 API 가용성을 잠식하지 않게 한다.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ConcurrencyLimitFilter implements WebFilter {

    private static final String ACTUATOR_PREFIX = "/actuator";
    private static final String SSE_PATH_SUFFIX = "/status/stream";
    // 과부하 시 거절 건마다 WARN을 남기면 로그 자체가 부하 유발원이 된다(리뷰 지적) - 카운터는
    // Micrometer로 매 건 저비용 집계하고, WARN 로그는 이 값마다 하나씩만 "누적 거절 수"와
    // 함께 남긴다. 상한 초과가 시작된 첫 건은 항상 즉시 로깅해 장애 시작 시점을 놓치지 않는다.
    private static final long REJECT_LOG_SAMPLE_RATE = 100L;

    private final int maxInFlight;
    private final int sseMaxInFlight;
    private final ApiErrorResponseWriter responseWriter;
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicInteger sseInFlight = new AtomicInteger(0);
    private final AtomicLong rejectedTotal = new AtomicLong(0);
    private final AtomicLong rejectedTotalSse = new AtomicLong(0);

    public ConcurrencyLimitFilter(ConcurrencyLimitProperties properties,
                                  ApiErrorResponseWriter responseWriter,
                                  MeterRegistry meterRegistry) {
        this.maxInFlight = properties.maxInFlightRequests();
        this.sseMaxInFlight = properties.sseMaxInFlightRequests();
        this.responseWriter = responseWriter;
        meterRegistry.gauge("gateway.concurrency.in-flight", Tags.of("kind", "control"), inFlight);
        meterRegistry.gauge("gateway.concurrency.in-flight", Tags.of("kind", "sse"), sseInFlight);
        meterRegistry.gauge("gateway.concurrency.rejected", Tags.of("kind", "control"), rejectedTotal);
        meterRegistry.gauge("gateway.concurrency.rejected", Tags.of("kind", "sse"), rejectedTotalSse);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith(ACTUATOR_PREFIX)) {
            return chain.filter(exchange);
        }

        boolean isSse = path.endsWith(SSE_PATH_SUFFIX);
        AtomicInteger counter = isSse ? sseInFlight : inFlight;
        int limit = isSse ? sseMaxInFlight : maxInFlight;

        int current = counter.incrementAndGet();
        if (current > limit) {
            counter.decrementAndGet();
            logRejected(isSse, current - 1, limit, path);
            return responseWriter.write(
                    exchange,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "GATEWAY_CONCURRENCY_LIMIT_EXCEEDED",
                    "서버가 처리할 수 있는 동시 요청 수를 초과했습니다. 잠시 후 다시 시도해 주세요.");
        }
        return chain.filter(exchange).doFinally(signal -> counter.decrementAndGet());
    }

    private void logRejected(boolean isSse, int currentInFlight, int limit, String path) {
        AtomicLong counter = isSse ? rejectedTotalSse : rejectedTotal;
        long total = counter.incrementAndGet();
        // 첫 건(total==1)은 즉시, 이후로는 표본만 - Micrometer 게이지가 정확한 누적치를 계속
        // 들고 있으므로 로그가 빠뜨린 건도 관측에서 유실되지 않는다.
        if (total == 1L || total % REJECT_LOG_SAMPLE_RATE == 0L) {
            log.warn("[gateway-concurrency-limit] rejected(sampled 1/{}) kind={} inFlight={} max={} path={} rejectedTotal={}",
                    REJECT_LOG_SAMPLE_RATE, isSse ? "sse" : "control", currentInFlight, limit, path, total);
        }
    }
}
