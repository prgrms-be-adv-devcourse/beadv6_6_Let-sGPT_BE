package com.openat.queue.application.service

import com.openat.queue.application.dto.QueueStatusInfo
import com.openat.queue.application.usecase.AdmitWaitersUseCase
import com.openat.queue.application.usecase.DecideQueueUseCase
import com.openat.queue.application.usecase.EnterQueueUseCase
import com.openat.queue.application.usecase.GetQueueStatusUseCase
import com.openat.queue.domain.model.AdmittedEntry
import com.openat.queue.domain.model.DecisionChoice
import com.openat.queue.domain.model.QueueStatus
import com.openat.queue.domain.model.WaitingTicket
import com.openat.queue.domain.repository.ConfirmedSalesRepository
import com.openat.queue.domain.repository.StockRepository
import com.openat.queue.domain.repository.WaitingQueueRepository
import com.openat.queue.infrastructure.config.QueueProperties
import java.time.Instant
import org.springframework.stereotype.Service

/**
 * 대기열 도메인 서비스. ZSET 조작(포트 호출)·입장 판정 로직을 여기 모아
 * 통신 방식(MVC/폴링, 추후 WebFlux/SSE)과 완전히 분리한다.
 *
 * 재고 인지형 입장 제어: 입장 여부는 사용자 수가 아니라 요청 수량 기준으로, product의
 * 실재고를 [StockRepository]로 읽어(쓰지 않음) 판정한다. 실제 입장 가부는
 * [WaitingQueueRepository.admitBatch]가 원자적 Lua에서 결정한다.
 *
 * 대화형 결정: 요청 수량만큼 지금 당장 못 받는 상황이면 [QueueStatus.DECISION_REQUIRED]로
 * 알리고, [DecideQueueUseCase]로 응답(WAIT/PARTIAL/GIVE_UP)을 받는다. "낙관적 최대"
 * (총재고 - 확정)는 [ConfirmedSalesRepository]가 제공한다.
 */
@Service
class QueueService(
    private val waitingQueueRepository: WaitingQueueRepository,
    private val stockRepository: StockRepository,
    private val confirmedSalesRepository: ConfirmedSalesRepository,
    private val queueProperties: QueueProperties,
) : EnterQueueUseCase, GetQueueStatusUseCase, AdmitWaitersUseCase, DecideQueueUseCase {

    override fun admitBatch(dropId: String): List<AdmittedEntry> =
        waitingQueueRepository.admitBatch(
            dropId,
            queueProperties.admission.batchSize,
            queueProperties.admission.ttlSeconds,
        )

    override fun enter(dropId: String, userId: String, quantity: Int): QueueStatusInfo {
        // 이미 미소진 입장권을 보유 중(READY)이면 재등록하지 않는다. 재등록을 허용하면 대기열에
        // 다시 쌓였다가 다음 admit tick에서 같은 사용자가 또 admitBatch에 뽑혀 outstanding이
        // 중복 가산되는 회계 오류로 이어진다(admit.lua에도 동일 목적의 방어를 추가로 넣었다 -
        // 이 체크와 admit tick 사이의 좁은 레이스까지 막기 위한 이중 방어).
        if (waitingQueueRepository.admittedQuantityOf(dropId, userId) == null) {
            // 이미 소진된 드롭이면 대기열에 넣지도 않고 바로 SOLD_OUT을 알린다 - 그냥
            // enqueueOrFastAdmit을 호출해버리면 대기열에 등록됐다가 다음 폴링에야 소진을
            // 알게 되거나(체감 지연), ticketOf가 없어 resolveStatus가 NOT_IN_QUEUE로
            // 잘못 판정하는 함정이 있다(대기열에 한 번도 안 들어간 사람이 "나갔다"는 오표현).
            val reason = soldOutReason(dropId)
            if (reason != null) {
                return QueueStatusInfo(
                    QueueStatus.SOLD_OUT,
                    rank = null,
                    totalWaiting = null,
                    quantity = null,
                    grantableNow = null,
                    optimisticMax = null,
                    pollIntervalMs = queueProperties.polling.intervalMs,
                    soldOutReason = reason.name,
                )
            }
            // 정적 hot-drops 목록 없이 모든 드롭에 균일 적용 - 대기 중인 사람이 없고 재고가
            // 즉시 가용하면 대기열 없이 바로 입장권을 발급한다(enqueueOrFastAdmit 참고).
            // 반환값은 무시해도 안전하다: 아래 resolveStatus가 admittedQuantityOf/ticketOf를
            // 다시 읽어 최신 상태를 그대로 반영하기 때문(기존 enqueue() 호출부도 동일한 방식).
            waitingQueueRepository.enqueueOrFastAdmit(
                dropId, userId, quantity, queueProperties.admission.ttlSeconds, Instant.now(),
            )
        }
        return resolveStatus(dropId, userId, touchHeartbeat = false)
    }

    override fun status(dropId: String, userId: String): QueueStatusInfo =
        resolveStatus(dropId, userId, touchHeartbeat = true)

    override fun decide(dropId: String, userId: String, choice: DecisionChoice): QueueStatusInfo {
        when (choice) {
            DecisionChoice.WAIT -> waitingQueueRepository.markWaitConfirmed(dropId, userId)
            DecisionChoice.GIVE_UP -> waitingQueueRepository.removeFromQueue(dropId, userId)
            // 실패(그 사이 재고가 완전히 사라짐)해도 별도 처리 불필요 - 아래 resolveStatus가
            // 최신 상태를 다시 계산해서 보여준다(예: WAITING 또는 다시 DECISION_REQUIRED).
            DecisionChoice.PARTIAL -> waitingQueueRepository.admitSingle(
                dropId, userId, queueProperties.admission.ttlSeconds,
            )
        }
        return resolveStatus(dropId, userId, touchHeartbeat = false)
    }

    private fun resolveStatus(dropId: String, userId: String, touchHeartbeat: Boolean): QueueStatusInfo {
        val pollIntervalMs = queueProperties.polling.intervalMs

        val admittedQuantity = waitingQueueRepository.admittedQuantityOf(dropId, userId)
        if (admittedQuantity != null) {
            return QueueStatusInfo(
                QueueStatus.READY,
                rank = null,
                totalWaiting = null,
                quantity = admittedQuantity,
                grantableNow = null,
                optimisticMax = null,
                pollIntervalMs = pollIntervalMs,
            )
        }

        val ticket = waitingQueueRepository.ticketOf(dropId, userId)
            ?: return QueueStatusInfo(
                QueueStatus.NOT_IN_QUEUE,
                rank = null,
                totalWaiting = null,
                quantity = null,
                grantableNow = null,
                optimisticMax = null,
                pollIntervalMs = pollIntervalMs,
            )

        if (touchHeartbeat) {
            waitingQueueRepository.touchHeartbeat(dropId, userId, Instant.now())
        }

        val reason = soldOutReason(dropId)
        if (reason != null) {
            return QueueStatusInfo(
                QueueStatus.SOLD_OUT,
                rank = null,
                totalWaiting = null,
                quantity = ticket.quantity,
                grantableNow = null,
                optimisticMax = null,
                pollIntervalMs = pollIntervalMs,
                soldOutReason = reason.name,
            )
        }

        val snapshot = stockRepository.snapshotOf(dropId)
            // 재고 캐시가 아직 없음(워밍 전) - 성급히 판단하지 않고 평범하게 대기시킨다.
            ?: return waitingInfo(ticket, grantableNow = null, optimisticMax = null, pollIntervalMs)

        val available = snapshot.remaining - waitingQueueRepository.outstandingOf(dropId)
        if (available >= ticket.quantity) {
            // 요청 수량이 전부 감당될 만큼 가용하다 - 곧 admit tick에서 정상 입장될 것이므로
            // 굳이 결정을 묻지 않는다.
            return waitingInfo(ticket, grantableNow = null, optimisticMax = null, pollIntervalMs)
        }

        // 공정성 방어: 엄격한 FIFO 정책상 지금 내 차례(rank 0)가 아니면 결정을 묻지 않는다.
        // admit.lua도 이제 맨 앞사람이 안 풀리면 뒷사람은 아예 보지도 않고 그 자리에서 멈추므로
        // (새치기 불가), rank>0인 사람에게 PARTIAL을 허용해버리면 아직 자기 차례도 안 된 사람이
        // 앞사람을 제치고 가용 재고를 가로채는 셈이 된다 - "순번대로, 못 든 사람은 대기"라는 이
        // 시스템의 대전제를 깨는 결함이다. decide-partial.lua에도 같은 rank==0 검사를 원자적으로
        // 넣어 이 앱 레이어 체크와 그 사이의 레이스(예: 막 앞사람이 빠져나가 방금 rank 0이 된
        // 순간)까지 막는다(이중 방어, admit.lua의 outstanding 중복가산 방어와 동일한 패턴).
        if (ticket.rank > 0) {
            return waitingInfo(ticket, grantableNow = null, optimisticMax = null, pollIntervalMs)
        }

        val grantableNow = maxOf(available, 0)
        val total = confirmedSalesRepository.totalOf(dropId)
        val confirmed = confirmedSalesRepository.confirmedOf(dropId)
        val optimisticMax = total?.let { it - confirmed }

        val isShortfall = optimisticMax != null && optimisticMax < ticket.quantity
        if (!isShortfall && waitingQueueRepository.hasConfirmedWait(dropId, userId)) {
            // 이미 "기다리겠다"고 답했고, 아직 진짜 불가능(SHORTFALL)해진 것도 아니다 -
            // 같은 질문을 반복하지 않고 조용히 대기시킨다.
            return waitingInfo(ticket, grantableNow, optimisticMax, pollIntervalMs)
        }

        // PARTIAL_OR_WAIT(처음 부족을 마주함) 또는 SHORTFALL(기다리다 상한이 요청량 밑으로
        // 떨어짐 - WAIT 확정 여부와 무관하게 항상 재질의) - 클라이언트가 optimisticMax와
        // quantity를 비교해 어떤 다이얼로그를 보여줄지 스스로 판단한다.
        return QueueStatusInfo(
            QueueStatus.DECISION_REQUIRED,
            rank = ticket.rank,
            totalWaiting = ticket.totalWaiting,
            quantity = ticket.quantity,
            grantableNow = grantableNow,
            optimisticMax = optimisticMax,
            pollIntervalMs = pollIntervalMs,
        )
    }

    private fun waitingInfo(
        ticket: WaitingTicket,
        grantableNow: Long?,
        optimisticMax: Long?,
        pollIntervalMs: Long,
    ): QueueStatusInfo = QueueStatusInfo(
        QueueStatus.WAITING,
        rank = ticket.rank,
        totalWaiting = ticket.totalWaiting,
        quantity = ticket.quantity,
        grantableNow = grantableNow,
        optimisticMax = optimisticMax,
        pollIntervalMs = pollIntervalMs,
    )

    /**
     * "더 이상 입장 기회가 없다"를 통지하는 사유. 선재고 선점 모델(주문 *생성* 시점에 이미
     * remaining이 차감되고, 결제까지 끝나야 confirmed로 확정됨) 기준으로:
     *
     * 1. [SoldOutReason.CLOSED] - 드롭이 마감(closeAt 경과). 시간이 끝나 더 이상 주문 자체가
     *    불가능해진 확정적인 신호.
     * 2. [SoldOutReason.STOCK_EXHAUSTED] - 직접 살 수 있는 재고도 없고(`remaining<=0`),
     *    선점됐지만 아직 결제 미완료라 반환 여지가 있는 "미확정 재고"
     *    (`unconfirmedReserved = (total-remaining)-confirmed`)도 없을 때. `remaining>0`이면
     *    확정/미확정 계산도 필요 없이 바로 소진이 아니라고 판단한다(가장 흔한 정상 경로를
     *    최소 계산으로 처리). 미확정 재고가 남아있는 동안은, 그 결제가 실패해 재고가 돌아올
     *    가능성이 있으므로 성급히 포기시키지 않는다.
     *
     * `총재고`/`확정 수량` 조회가 실패하거나(product 응답 불가 등) 재고 캐시가 아직 없으면
     * 안전한 쪽으로 저하한다 - SOLD_OUT을 내리지 않고 그냥 계속 대기시킨다(null 반환).
     *
     * 입장에 성공한 사람은 애초에 재고 인지형 admit.lua가 가용 재고 이하만 통과시키므로
     * 이 상태를 절대 보지 않는다("입장한 사람은 품절을 안 본다").
     */
    private fun soldOutReason(dropId: String): SoldOutReason? {
        val snapshot = stockRepository.snapshotOf(dropId)
        val closeAt = snapshot?.closeAt
        if (closeAt != null && !Instant.now().isBefore(closeAt)) {
            return SoldOutReason.CLOSED
        }

        val remaining = snapshot?.remaining ?: return null // 캐시 미워밍 - 안전하게 저하(대기 유지)
        if (remaining > 0) return null // 직접 구매 가능한 재고가 있음 - 확정/미확정 계산도 필요 없이 소진 아님

        val total = confirmedSalesRepository.totalOf(dropId) ?: return null
        val confirmed = confirmedSalesRepository.confirmedOf(dropId)
        val unconfirmedReserved = (total - remaining) - confirmed
        return if (unconfirmedReserved <= 0) SoldOutReason.STOCK_EXHAUSTED else null
    }

    private enum class SoldOutReason {
        CLOSED,
        STOCK_EXHAUSTED,
    }
}
