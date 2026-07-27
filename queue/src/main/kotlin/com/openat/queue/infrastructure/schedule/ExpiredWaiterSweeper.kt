package com.openat.queue.infrastructure.schedule

import com.openat.queue.domain.repository.WaitingQueueRepository
import com.openat.queue.infrastructure.config.QueueProperties
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
 * 폴링 하트비트가 끊긴(마지막 폴링 이후 `heartbeat-ttl-ms` 경과) 대기자의 자리를 회수하고,
 * DECISION_REQUIRED에 응답하지 않는(하트비트는 살아있는) 맨 앞 사용자를 타임아웃 처리한다.
 * 클라이언트가 브라우저를 닫는 등으로 이탈해도 대기열에 유령 인원이 남지 않도록 한다.
 *
 * WebFlux+SSE 전환: SSE 커넥션은 [com.openat.queue.application.service.QueueStreamService]가
 * 연결 종료를 직접 감지해 즉시 회수하므로, 이 스위퍼는 더 이상 "이탈 감지의 유일한 수단"이
 * 아니라 안전망이다(즉시 회수가 실패하거나, 애초에 폴링(`GET /status`, 회귀호환용)으로 붙은
 * 클라이언트처럼 "연결"이라는 개념이 없는 경우까지 여전히 커버해야 하기 때문에 없애지 않았다).
 *
 * 결정 타임아웃이 별도로 필요한 이유: 하트비트 스위퍼는 "폴링/스트림이 끊긴" 사람만 잡는데,
 * 탭만 열어둔 무응답자는 연결이 계속되므로 안 걸린다 - 엄격한 FIFO에서 rank 0의 미결정은 큐
 * 전체 정지이므로 이 구멍을 sweep-decision.lua(무응답 + 여전히 재고 부족일 때만 제거, WAIT
 * 확정자 제외)로 닫는다.
 *
 * 정적 hot-drops 목록이 없으므로 [WaitingQueueRepository.activeDropIds]로 대상을 찾고, 각
 * dropId를 정리한 직후 [WaitingQueueRepository.pruneIfIdle]로 완전히 유휴 상태가 됐는지
 * 확인해 그 레지스트리를 정리한다 - 이 컴포넌트가 "다 빠진 dropId를 지우는" 유일한 소유자다.
 *
 * feature/queue-remaining-sync(코루틴 전환): `@Scheduled` 전용 스레드에서 `runBlocking`으로
 * 시작해 끝까지 기다린다(AdmissionScheduler와 동일한 근거 - 이 스레드는 Netty 이벤트루프가
 * 아니다). dropId별 정리는 `async`로 동시에 실행해 예전 `Flux.flatMap`의 동시성을 유지하고,
 * [sweepOne] 안의 두 개별 조회(하트비트 만료/결정 타임아웃)도 `async`로 동시에 보내
 * 예전 `Mono.zip`과 동일한 왕복 수를 유지한다.
 */
@Component
class ExpiredWaiterSweeper(
    private val waitingQueueRepository: WaitingQueueRepository,
    private val queueProperties: QueueProperties,
    private val queueEventPublisher: QueueEventPublisher,
) {

    private val log = LoggerFactory.getLogger(ExpiredWaiterSweeper::class.java)

    @Scheduled(fixedDelayString = "\${queue.waiting.sweep-interval-ms}")
    fun sweep() = runBlocking {
        val now = Instant.now()
        val dropIds = waitingQueueRepository.activeDropIds()
        coroutineScope {
            dropIds.map { dropId -> async { sweepOne(dropId, now) } }.awaitAll()
        }
    }

    private suspend fun sweepOne(dropId: String, now: Instant) = coroutineScope {
        val expiredRemoved = async {
            val removed = waitingQueueRepository.sweepExpired(dropId, now, queueProperties.waiting.heartbeatTtlMs)
            if (removed > 0) log.info("[queue-sweep] dropId={} removed={}", dropId, removed)
            removed > 0
        }
        val decisionTimedOut = async {
            val timedOutUser = waitingQueueRepository.sweepDecisionTimeout(dropId, now, queueProperties.decision.timeoutMs)
            if (timedOutUser != null) {
                log.info(
                    "[queue-decision-sweep] dropId={} userId={} 결정 무응답 타임아웃({}ms) - 대기열에서 제거",
                    dropId, timedOutUser, queueProperties.decision.timeoutMs,
                )
            }
            timedOutUser != null
        }
        val changed = expiredRemoved.await() || decisionTimedOut.await()
        // SSE 전환분: 이탈자가 회수되면 뒤 대기자들의 rank/grantableNow가 바뀔 수 있으므로 신호.
        if (changed) queueEventPublisher.publishChanged(dropId)

        val pruned = waitingQueueRepository.pruneIfIdle(dropId)
        if (pruned) log.debug("[queue-sweep] dropId={} 완전 유휴 - active-drops에서 제거", dropId)
    }
}
