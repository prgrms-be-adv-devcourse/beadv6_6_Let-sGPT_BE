package com.openat.queue.infrastructure.schedule

import com.openat.queue.domain.repository.WaitingQueueRepository
import com.openat.queue.infrastructure.config.QueueProperties
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 폴링 하트비트가 끊긴(마지막 폴링 이후 `heartbeat-ttl-ms` 경과) 대기자의 자리를 회수한다.
 * 클라이언트가 브라우저를 닫는 등으로 이탈해도 대기열에 유령 인원이 남지 않도록 한다.
 *
 * 정적 hot-drops 목록이 없으므로 [WaitingQueueRepository.activeDropIds]로 대상을 찾고, 각
 * dropId를 정리한 직후 [WaitingQueueRepository.pruneIfIdle]로 완전히 유휴 상태가 됐는지
 * 확인해 그 레지스트리를 정리한다 - 이 컴포넌트가 "다 빠진 dropId를 지우는" 유일한 소유자다.
 *
 * infrastructure 계층의 스케줄 트리거이지만 통신 프로토콜과 무관하므로
 * WebFlux 전환 시에도 변경 없이 재사용된다.
 */
@Component
class ExpiredWaiterSweeper(
    private val waitingQueueRepository: WaitingQueueRepository,
    private val queueProperties: QueueProperties,
) {

    private val log = LoggerFactory.getLogger(ExpiredWaiterSweeper::class.java)

    @Scheduled(fixedDelayString = "\${queue.waiting.sweep-interval-ms}")
    fun sweep() {
        val now = Instant.now()
        waitingQueueRepository.activeDropIds().forEach { dropId ->
            val removed = waitingQueueRepository.sweepExpired(
                dropId,
                now,
                queueProperties.waiting.heartbeatTtlMs,
            )
            if (removed > 0) {
                log.info("[queue-sweep] dropId={} removed={}", dropId, removed)
            }
            if (waitingQueueRepository.pruneIfIdle(dropId)) {
                log.debug("[queue-sweep] dropId={} 완전 유휴 - active-drops에서 제거", dropId)
            }
        }
    }
}
