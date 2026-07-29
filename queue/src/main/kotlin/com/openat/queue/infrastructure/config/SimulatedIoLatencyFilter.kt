package com.openat.queue.infrastructure.config

import java.time.Duration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * **부하테스트 전용 계측기** - 느린 다운스트림 I/O를 흉내 내기 위해 요청마다 고정 지연을 넣는다.
 * `queue.loadtest.simulated-io-latency-ms`(기본 0)가 0보다 클 때만 동작하므로 운영/개발 기본
 * 동작에는 아무 영향이 없다.
 *
 * **왜 필요한가**: MVC(스레드 1개당 요청 1개)와 WebFlux(이벤트 루프)의 구조적 차이는 "요청이
 * I/O를 기다리는 동안 스레드를 붙잡고 있는가"에서 갈린다. 그런데 이 프로젝트의 대기열 API는
 * Redis만 때리기 때문에 응답이 3~30ms로 너무 빨라서, 개발 노트북에서는 **스레드가 포화되기
 * 전에 CPU가 먼저 100%에 닿는다**(실측: 동시접속 3,000명에서 호스트 CPU 평균 75~79%, 최대
 * 100%). 즉 이론이 말하는 "동시 연결 수가 병목인 상황" 자체를 만들 수 없다.
 *
 * 이 필터는 그 조건을 인위적으로 만든다 - 요청당 수백 ms를 "기다리게" 해서 CPU는 한가한 채로
 * 동시 처리 요청 수가 병목이 되게 한다. 실제 운영에서 queue가 product 서비스를 REST로 호출하는
 * 구간(`ProductClientConfig`)이 느려지는 상황과 같은 성격이다.
 *
 * **중요 - 두 스테이지의 구현이 의도적으로 다르다**:
 * - WebFlux(이 파일): [Mono.delay]로 기다린다. 이벤트 루프 스레드를 붙잡지 않는다(논블로킹).
 * - MVC(stage1의 같은 이름 서블릿 필터): `Thread.sleep`으로 기다린다. 요청 스레드를 붙잡는다(블로킹).
 *
 * 이 차이는 편법이 아니라 **정확히 두 아키텍처의 실제 동작을 재현한 것**이다. 블로킹 I/O를 쓰는
 * MVC는 다운스트림을 기다리는 동안 스레드를 점유하고, 리액티브 스택은 점유하지 않는다.
 *
 * 액추에이터 경로는 지연에서 제외한다 - 헬스체크/메트릭 수집까지 느려지면 측정 자체가 왜곡된다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class SimulatedIoLatencyFilter(
    @Value("\${queue.loadtest.simulated-io-latency-ms:0}") private val delayMs: Long,
) : WebFilter {

    private val log = LoggerFactory.getLogger(SimulatedIoLatencyFilter::class.java)

    init {
        if (delayMs > 0) {
            log.warn(
                "[loadtest] 모의 I/O 지연 활성화: 요청당 {}ms (논블로킹 대기) - 부하테스트 전용 설정이다",
                delayMs,
            )
        }
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (delayMs <= 0 || exchange.request.path.value().startsWith("/actuator")) {
            return chain.filter(exchange)
        }
        return Mono.delay(Duration.ofMillis(delayMs)).then(chain.filter(exchange))
    }
}
