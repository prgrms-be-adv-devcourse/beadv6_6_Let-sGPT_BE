package com.openat.queue.infrastructure.config

import com.openat.queue.domain.repository.StockRepository
import com.openat.queue.domain.repository.WaitingQueueRepository
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tags
import java.util.concurrent.ConcurrentHashMap
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * 대기열 운영 지표(Micrometer Gauge). 정적 hot-drops 목록이 없으므로 기동 시 일괄 등록할 수
 * 없다 - 대신 [AdmissionScheduler]가 각 활성 dropId를 처리하기 전에 [ensureRegistered]를
 * 호출해 "처음 보는 dropId"에 한해 온디맨드로 등록한다(중복 등록은 [registered] SET으로 방지).
 *
 * - `queue.waiting.size{dropId=...}`: 현재 대기 인원(ZCARD).
 * - `queue.outstanding{dropId=...}`: 미소진 입장권 수량 합 - 재고 인지형 입장 제어가 실제로
 *   "점유"를 정확히 추적하고 있는지 확인하는 핵심 지표.
 * - `queue.stock.remaining{dropId=...}`: product 실재고(참고용, 큐가 쓰지 않고 읽기만 함).
 */
@Component
class QueueMetricsConfig(
    private val meterRegistry: MeterRegistry,
    private val waitingQueueRepository: WaitingQueueRepository,
    private val stockRepository: StockRepository,
    private val redisTemplate: StringRedisTemplate,
) {

    private val registered = ConcurrentHashMap.newKeySet<String>()

    fun ensureRegistered(dropId: String) {
        if (!registered.add(dropId)) {
            return // 이미 등록됨(Micrometer의 gauge()는 같은 이름+태그 재등록을 멱등하게 무시하지만,
            // 매 tick마다 불필요한 호출 자체를 줄이기 위해 여기서 먼저 걸러낸다).
        }

        meterRegistry.gauge(
            "queue.waiting.size",
            Tags.of("dropId", dropId),
            waitingQueueRepository,
        ) { repository -> repository.sizeOf(dropId).toDouble() }

        meterRegistry.gauge(
            "queue.outstanding",
            Tags.of("dropId", dropId),
            redisTemplate,
        ) { template -> (template.opsForValue().get("outstanding:$dropId")?.toDoubleOrNull() ?: 0.0) }

        meterRegistry.gauge(
            "queue.stock.remaining",
            Tags.of("dropId", dropId),
            stockRepository,
        ) { repository -> (repository.snapshotOf(dropId)?.remaining ?: -1L).toDouble() }
    }
}
