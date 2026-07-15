package com.openat.queue.infrastructure.schedule

import com.openat.queue.application.usecase.AdmitWaitersUseCase
import com.openat.queue.domain.repository.WaitingQueueRepository
import com.openat.queue.infrastructure.config.QueueMetricsConfig
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 재고 인지형 입장 제어: "초당 N명" 고정 유량이 아니다. tick마다 admit.lua가 그 시점
 * product 실재고(remaining) - 미소진 입장권 수량(outstanding)을 계산해, 그만큼만 대기열
 * 앞에서부터 원자적으로 입장시킨다. `queue.admission.batch-size`는 이제 "한 tick에 살펴볼
 * 후보 상한(maxScan)"이라는 성능 보호용 의미로 재해석된다 - 실제 입장 수는 재고가 결정한다.
 *
 * 정적 hot-drops 목록 대신 [WaitingQueueRepository.activeDropIds]로 "지금 대기자가 있거나
 * 미소진 입장권이 남아있는" dropId를 동적으로 찾는다 - 경쟁이 없는 드롭은 애초에 이 목록에
 * 들어오지 않으므로(즉시 입장 fast path로 대기열을 거치지 않음), 이 스케줄러가 순회하는
 * 대상은 "실제로 처리할 일이 있는" dropId로 자연히 제한된다.
 *
 * 주의: 이 스케줄러가 파드마다 독립 실행돼도 admit.lua의 원자성 덕분에 중복 입장/오버셀은
 * 나지 않는다(같은 outstanding/remaining을 놓고 경쟁하므로). 다만 tick 주기 자체가 파드마다
 * 겹치면 Redis 호출량이 배가되니, 멀티 파드 스케줄러 단일화(분산 락/리더 선출)는 여전히
 * 확장 여지로 남겨둔다.
 */
@Component
class AdmissionScheduler(
    private val admitWaitersUseCase: AdmitWaitersUseCase,
    private val waitingQueueRepository: WaitingQueueRepository,
    private val queueMetricsConfig: QueueMetricsConfig,
    private val meterRegistry: MeterRegistry,
) {

    private val log = LoggerFactory.getLogger(AdmissionScheduler::class.java)

    @Scheduled(fixedDelayString = "\${queue.admission.interval-ms}")
    fun admit() {
        waitingQueueRepository.activeDropIds().forEach { dropId ->
            queueMetricsConfig.ensureRegistered(dropId)
            val admitted = admitWaitersUseCase.admitBatch(dropId)
            if (admitted.isNotEmpty()) {
                val totalQuantity = admitted.sumOf { it.quantity }
                log.info(
                    "[queue-admit] dropId={} userCount={} totalQuantity={} entries={}",
                    dropId, admitted.size, totalQuantity, admitted,
                )
                meterRegistry.counter("queue.admission.count", Tags.of("dropId", dropId))
                    .increment(admitted.size.toDouble())
                meterRegistry.counter("queue.admission.quantity", Tags.of("dropId", dropId))
                    .increment(totalQuantity.toDouble())
            }
        }
    }
}
