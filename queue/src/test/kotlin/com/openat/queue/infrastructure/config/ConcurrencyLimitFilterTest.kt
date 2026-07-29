package com.openat.queue.infrastructure.config

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * SSE(`/status/stream`)와 일반 제어 API가 동시 처리 한도를 공유하지 않는지 검증한다.
 *
 * 배경(리뷰 지적): SSE는 연결 수명 내내 in-flight 슬롯을 점유한다. 카운터를 공유하면 SSE
 * 구독자가 상한을 다 채운 순간부터는 GIVE_UP 같은 상태 변경 요청도 429로 거절돼, 사용자가
 * 스스로 자리를 반납할 방법이 없어진다("포기하고 싶은데 포기 요청 자체가 막힌다"). 이 테스트는
 * SSE 한도를 채운 상태에서도 제어 API가 여전히 통과하는지(그 반대도) 고정한다.
 */
@DisplayName("ConcurrencyLimitFilter - SSE/제어 API 슬롯 분리")
class ConcurrencyLimitFilterTest {

    @Test
    @DisplayName("SSE 한도를 채운 상태에서도 일반 제어 API(GIVE_UP 등)는 여전히 통과한다")
    fun sseAtLimit_controlApiStillPasses() = runBlocking<Unit> {
        val filter = newFilter(maxInFlight = 2000, sseMaxInFlight = 1)

        // SSE 슬롯 하나를 "점유 중"으로 만든다 - 절대 완료되지 않는 Mono.never()로 붙잡아
        // doFinally가 카운터를 반환하지 않는 상태를 흉내낸다(실제 SSE 연결이 열려있는 것과 동일).
        val sseExchange = exchangeFor("/api/v1/queues/drop-1/status/stream")
        filter.filter(sseExchange, neverCompletingChain()).subscribe()

        // 그 상태에서 GIVE_UP(decision) 요청이 들어온다 - SSE와 경로가 다르므로 별도 카운터를 쓴다.
        val decisionExchange = exchangeFor("/api/v1/queues/drop-1/decision")
        filter.filter(decisionExchange, passthroughChain()).awaitSingleOrNull()

        assertThat(decisionExchange.response.statusCode).isNull() // 거절이면 429가 찍힘 - null이면 체인 통과
    }

    @Test
    @DisplayName("SSE 한도를 초과하면 이후 SSE 요청만 429고, 제어 API 한도와는 무관하다")
    fun sseOverLimit_rejectsOnlySse() = runBlocking<Unit> {
        val filter = newFilter(maxInFlight = 2000, sseMaxInFlight = 1)
        filter.filter(exchangeFor("/api/v1/queues/drop-1/status/stream"), neverCompletingChain()).subscribe()

        val secondSse = exchangeFor("/api/v1/queues/drop-1/status/stream")
        filter.filter(secondSse, passthroughChain()).awaitSingleOrNull()

        assertThat(secondSse.response.statusCode).isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    @DisplayName("제어 API 한도를 채워도 SSE 신규 연결은 별도 카운터라 여전히 통과한다")
    fun controlAtLimit_sseStillPasses() = runBlocking<Unit> {
        val filter = newFilter(maxInFlight = 1, sseMaxInFlight = 2000)
        filter.filter(exchangeFor("/api/v1/queues/drop-1/decision"), neverCompletingChain()).subscribe()

        val sseExchange = exchangeFor("/api/v1/queues/drop-1/status/stream")
        filter.filter(sseExchange, passthroughChain()).awaitSingleOrNull()

        assertThat(sseExchange.response.statusCode).isNull()
    }

    @Test
    @DisplayName("액추에이터 경로는 두 카운터 어느 쪽도 소비하지 않고 항상 통과한다")
    fun actuatorPath_alwaysBypassesBothCounters() = runBlocking<Unit> {
        val filter = newFilter(maxInFlight = 0, sseMaxInFlight = 0)

        val exchange = exchangeFor("/actuator/health")
        filter.filter(exchange, passthroughChain()).awaitSingleOrNull()

        assertThat(exchange.response.statusCode).isNull()
    }

    private fun newFilter(maxInFlight: Int, sseMaxInFlight: Int): ConcurrencyLimitFilter {
        val properties = QueueProperties(
            concurrency = QueueProperties.Concurrency(
                maxInFlightRequests = maxInFlight,
                sseMaxInFlightRequests = sseMaxInFlight,
            ),
        )
        return ConcurrencyLimitFilter(properties, ObjectMapper(), SimpleMeterRegistry())
    }

    private fun exchangeFor(path: String): MockServerWebExchange =
        MockServerWebExchange.from(MockServerHttpRequest.get(path).build())

    private fun passthroughChain(): WebFilterChain = WebFilterChain { Mono.empty() }

    /** 응답을 절대 완료하지 않는 체인 - 실제 SSE 연결이 열려있는 동안 슬롯을 계속 점유하는 것을 흉내낸다. */
    private fun neverCompletingChain(): WebFilterChain = WebFilterChain { Mono.never() }
}
