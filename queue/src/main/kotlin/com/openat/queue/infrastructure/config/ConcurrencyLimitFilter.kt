package com.openat.queue.infrastructure.config

import com.openat.common.error.ErrorResponse
import com.openat.queue.domain.error.QueueErrorCode
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.core.io.buffer.DataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import com.fasterxml.jackson.databind.ObjectMapper
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

/**
 * MVC(Tomcat)는 스레드 풀(운영 기준 50개)이 그 이상 몰리는 요청을 처리 시작 전 단계(OS 커널의
 * 가벼운 accept backlog)에 묶어둬서 "동시 처리 요청 수 제한"을 부수 효과로 갖는다 - 몰려도
 * 힙을 거의 안 쓴다. WebFlux/Netty에는 이런 상한이 기본적으로 없다 - 들어오는 연결을 원칙적으로
 * 전부 받아들여 처리를 시작하므로, 아주 작은 힙(운영 파드 기준 약 220MB)에서 동시접속이 수천
 * 단위로 몰리면 각 요청이 물고 있는 처리 상태(채널·버퍼·리액티브 파이프라인)가 쌓여
 * OutOfMemoryError로 이어진다.
 *
 * 실측으로 확인된 문제: t3.large(2vCPU) + 운영과 동일한 힙(220MB) 근사 환경에서 동시접속을
 * 계단식으로 올리자 약 5,000~6,000명 구간에서 3회 반복 전부 `OutOfMemoryError`로 서비스가
 * 다운됐다(같은 조건의 MVC+폴링은 3회 다 에러 0건으로 버팀 - 응답만 느려짐). 정밀 분석 결과,
 * "WebFlux는 스레드를 적게 쓴다"는 이론 자체는 계속 확인됐지만(스레드 수는 부하와 무관하게
 * 낮고 평평), 그건 메모리 축과는 무관한 별개의 자원 이야기였다 - MVC가 메모리 축에서
 * "우연히" 유리했던 것뿐, WebFlux가 메모리 축을 스스로 보호하는 장치는 원래 없었다.
 * (queue/loadtest/three-stage-story.md §4-4 "정직하게 밝히는 한계" 절 참고)
 *
 * 이 필터는 MVC가 부수 효과로 얻던 그 보호를 명시적으로 재현한다 - 동시 처리 중인 요청 수가
 * [QueueProperties.Concurrency.maxInFlightRequests]를 넘으면 처리를 아예 시작하지 않고
 * 즉시 429로 거절한다. 서비스 전체가 죽어서 모든 사용자가 못 쓰게 되는 것보다, 상한을 넘는
 * 일부 요청만 지연 없이 실패시키는 쪽이 낫다는 판단 - MVC가 넘치는 요청을 accept backlog에
 * "대기"시키는 것과 달리 이 필터는 "즉시 거절"을 택했다. WebFlux에는 스레드처럼 공짜로 쓸 수
 * 있는 대기 공간이 없고(대기시키려면 그 자체가 또 메모리를 쓴다), 즉시 거절이 재시도/폴백을
 * 유도하기에 더 명확한 신호이기 때문이다.
 *
 * `Ordered.HIGHEST_PRECEDENCE`로 등록해 인증·비즈니스 로직보다 먼저 카운트를 확인한다 -
 * 그래야 상한을 넘었을 때 뒤쪽 처리(Redis 호출 등)로 인한 추가 메모리 사용 자체를 막는다.
 *
 * 액추에이터 경로는 카운트에서 제외한다 - SSE는 연결 수명 내내 in-flight 슬롯을 점유하므로
 * 상한 근처에서는 이 필터가 사실상 "동시 SSE 구독자 상한"으로 작동한다. 제외하지 않으면
 * 부하가 상한을 넘겼을 때 liveness/readiness 프로브까지 429로 거절돼 k8s가 정상 동작 중인
 * 파드를 재시작시킨다([SimulatedIoLatencyFilter]와 동일한 이유로 동일하게 예외 처리).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ConcurrencyLimitFilter(
    queueProperties: QueueProperties,
    private val objectMapper: ObjectMapper,
) : WebFilter {

    private val log = LoggerFactory.getLogger(ConcurrencyLimitFilter::class.java)
    private val maxInFlight = queueProperties.concurrency.maxInFlightRequests
    private val inFlight = AtomicInteger(0)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (exchange.request.path.value().startsWith("/actuator")) {
            return chain.filter(exchange)
        }
        val current = inFlight.incrementAndGet()
        if (current > maxInFlight) {
            inFlight.decrementAndGet()
            log.warn("[concurrency-limit] rejected - inFlight={} max={} path={}", current - 1, maxInFlight, exchange.request.path)
            return reject(exchange)
        }
        return chain.filter(exchange)
            .doFinally { inFlight.decrementAndGet() }
    }

    private fun reject(exchange: ServerWebExchange): Mono<Void> {
        val response = exchange.response
        response.statusCode = HttpStatus.TOO_MANY_REQUESTS
        response.headers.contentType = MediaType.APPLICATION_JSON
        val body = ErrorResponse.of(QueueErrorCode.CONCURRENCY_LIMIT_EXCEEDED)
        val bytes = objectMapper.writeValueAsBytes(body)
        val bufferFactory: DataBufferFactory = response.bufferFactory()
        return response.writeWith(Mono.just(bufferFactory.wrap(bytes)))
    }
}
