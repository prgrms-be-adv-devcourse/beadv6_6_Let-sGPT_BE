package com.openat.apigateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

import com.openat.apigateway.error.ApiErrorResponseWriter;

/**
 * SSE({@code /status/stream})와 일반 제어 API가 동시 처리 한도를 공유하지 않는지 검증한다.
 *
 * <p>배경(리뷰 지적): SSE는 연결 수명 내내 in-flight 슬롯을 점유한다. 카운터를 공유하면 SSE
 * 구독자가 상한을 다 채운 순간부터는 GIVE_UP 같은 상태 변경 요청도 429로 거절돼, 사용자가
 * 스스로 자리를 반납할 방법이 없어진다("포기하고 싶은데 포기 요청 자체가 막힌다"). 이 테스트는
 * SSE 한도를 채운 상태에서도 제어 API가 여전히 통과하는지(그 반대도) 고정한다. queue 모듈의
 * {@code ConcurrencyLimitFilterTest}와 동일한 시나리오를 게이트웨이 쪽에서도 고정한다 - 게이트웨이는
 * SSE 응답을 그대로 프록시하는 경로라 같은 종류의 슬롯 점유 문제를 별도로 갖는다.
 */
@DisplayName("ConcurrencyLimitFilter - SSE/제어 API 슬롯 분리")
class ConcurrencyLimitFilterTest {

    private static final String SSE_PATH = "/api/v1/queues/drop-1/status/stream";
    private static final String DECISION_PATH = "/api/v1/queues/drop-1/decision";

    @Test
    @DisplayName("SSE 한도를 채운 상태에서도 일반 제어 API(GIVE_UP 등)는 여전히 통과한다")
    void sseAtLimit_controlApiStillPasses() {
        ConcurrencyLimitFilter filter = newFilter(2000, 1);

        // SSE 슬롯 하나를 "점유 중"으로 만든다 - 절대 완료되지 않는 Mono.never()로 붙잡아
        // doFinally가 카운터를 반환하지 않는 상태를 흉내낸다(실제 SSE 연결이 열려있는 것과 동일).
        MockServerWebExchange sseExchange = exchangeFor(SSE_PATH);
        filter.filter(sseExchange, neverCompletingChain()).subscribe();

        // 그 상태에서 GIVE_UP(decision) 요청이 들어온다 - SSE와 경로가 다르므로 별도 카운터를 쓴다.
        MockServerWebExchange decisionExchange = exchangeFor(DECISION_PATH);
        filter.filter(decisionExchange, passthroughChain()).block();

        assertThat(decisionExchange.getResponse().getStatusCode()).isNull(); // 거절이면 429 - null이면 체인 통과
    }

    @Test
    @DisplayName("SSE 한도를 초과하면 이후 SSE 요청만 429고, 제어 API 한도와는 무관하다")
    void sseOverLimit_rejectsOnlySse() {
        ConcurrencyLimitFilter filter = newFilter(2000, 1);
        filter.filter(exchangeFor(SSE_PATH), neverCompletingChain()).subscribe();

        MockServerWebExchange secondSse = exchangeFor(SSE_PATH);
        filter.filter(secondSse, passthroughChain()).block();

        assertThat(secondSse.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("제어 API 한도를 채워도 SSE 신규 연결은 별도 카운터라 여전히 통과한다")
    void controlAtLimit_sseStillPasses() {
        ConcurrencyLimitFilter filter = newFilter(1, 2000);
        filter.filter(exchangeFor(DECISION_PATH), neverCompletingChain()).subscribe();

        MockServerWebExchange sseExchange = exchangeFor(SSE_PATH);
        filter.filter(sseExchange, passthroughChain()).block();

        assertThat(sseExchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("액추에이터 경로는 두 카운터 어느 쪽도 소비하지 않고 항상 통과한다")
    void actuatorPath_alwaysBypassesBothCounters() {
        ConcurrencyLimitFilter filter = newFilter(0, 0);

        MockServerWebExchange exchange = exchangeFor("/actuator/health");
        filter.filter(exchange, passthroughChain()).block();

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    private ConcurrencyLimitFilter newFilter(int maxInFlight, int sseMaxInFlight) {
        ConcurrencyLimitProperties properties = new ConcurrencyLimitProperties(maxInFlight, sseMaxInFlight);
        return new ConcurrencyLimitFilter(
                properties, new ApiErrorResponseWriter(new ObjectMapper()), new SimpleMeterRegistry());
    }

    private MockServerWebExchange exchangeFor(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }

    private WebFilterChain passthroughChain() {
        return exchange -> Mono.empty();
    }

    /** 응답을 절대 완료하지 않는 체인 - 실제 SSE 연결이 열려있는 동안 슬롯을 계속 점유하는 것을 흉내낸다. */
    private WebFilterChain neverCompletingChain() {
        return exchange -> Mono.never();
    }
}
