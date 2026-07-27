package com.openat.queue.infrastructure.schedule

import com.openat.queue.domain.repository.WaitingQueueRepository
import com.openat.queue.infrastructure.persistence.QueueEventPublisher
import java.time.Instant
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 발급됐지만 소진되지 않은 입장권(admission ticket)의 TTL이 지나면 회수해 outstanding을
 * 되돌린다. 방치된 입장권이 영원히 실재고를 붙들고 있으면 뒤 대기자가 영영 못 들어오므로,
 * [ExpiredWaiterSweeper](대기 중 이탈)와 같은 철학의 자가치유를 "입장은 했지만 안 쓴 사람"에게도
 * 적용한 것이다. 즉시 입장 fast path(대기열을 거치지 않고 바로 발급된 입장권)도 대상이므로
 * [WaitingQueueRepository.activeDropIds]로 대상을 찾는다(정적 hot-drops 목록 없음).
 *
 * feature/queue-remaining-sync(코루틴 전환): 다른 두 스케줄러와 동일한 근거로 `runBlocking` +
 * `async`(dropId별 동시 처리)를 쓴다.
 */
@Component
class AdmittedTicketSweeper(
    private val waitingQueueRepository: WaitingQueueRepository,
    private val queueEventPublisher: QueueEventPublisher,
) {

    private val log = LoggerFactory.getLogger(AdmittedTicketSweeper::class.java)

    @Scheduled(fixedDelayString = "\${queue.admission.sweep-interval-ms}")
    fun sweep() = runBlocking {
        val now = Instant.now()
        val dropIds = waitingQueueRepository.activeDropIds()
        coroutineScope {
            dropIds.map { dropId ->
                async {
                    val reclaimed = waitingQueueRepository.sweepAdmittedTickets(dropId, now)
                    if (reclaimed > 0) {
                        log.info("[queue-admitted-sweep] dropId={} reclaimed={}", dropId, reclaimed)
                        // SSE 전환분: 회수된 만큼 outstanding이 줄어 뒤 대기자의 grantableNow가 오를 수 있다.
                        queueEventPublisher.publishChanged(dropId)
                    }
                }
            }.awaitAll()
        }
    }
}
