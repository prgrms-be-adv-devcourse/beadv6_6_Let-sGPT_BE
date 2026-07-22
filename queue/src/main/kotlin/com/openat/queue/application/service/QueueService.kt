package com.openat.queue.application.service

import com.openat.common.exception.BusinessException
import com.openat.queue.application.dto.QueueStatusInfo
import com.openat.queue.application.usecase.AdmitWaitersUseCase
import com.openat.queue.application.usecase.DecideQueueUseCase
import com.openat.queue.application.usecase.EnterQueueUseCase
import com.openat.queue.application.usecase.GetQueueStatusUseCase
import com.openat.queue.domain.error.QueueErrorCode
import com.openat.queue.domain.model.AdmittedEntry
import com.openat.queue.domain.model.DecisionChoice
import com.openat.queue.domain.model.DecisionState
import com.openat.queue.domain.model.DropStockSnapshot
import com.openat.queue.domain.model.QueueStatus
import com.openat.queue.domain.model.QueueStatusSnapshot
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
 * (총재고 - 확정)와 "품절 여부"(확정 < 총재고) 둘 다 [ConfirmedSalesRepository]가 제공하는
 * `total`/`confirmed`만으로 계산한다(핵심 발견: `confirmed < total`이 예전 `reserved - confirmed`
 * 기반 판정과 수학적으로 항상 동치임이 증명됨 - queue-remaining-sync 재설계 작업 참고).
 * "미확정(선점된) 재고" 자체는 여전히 `reserved`(order의 CREATED/CANCELLED 이벤트로 큐가
 * 집계)로 추적하지만, 이제 admission 관문(`remaining = total - reserved`)에서만 쓰이고
 * 이 서비스 계층의 품절 판정에는 더 이상 필요 없다.
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
        val stockSnapshot = stockRepository.snapshotOf(dropId)
        validateQuantity(stockSnapshot, quantity)
        // 이미 미소진 입장권을 보유 중(READY)이면 재등록하지 않는다. 재등록을 허용하면 대기열에
        // 다시 쌓였다가 다음 admit tick에서 같은 사용자가 또 admitBatch에 뽑혀 outstanding이
        // 중복 가산되는 회계 오류로 이어진다(admit.lua에도 동일 목적의 방어를 추가로 넣었다 -
        // 이 체크와 admit tick 사이의 좁은 레이스까지 막기 위한 이중 방어).
        if (waitingQueueRepository.admittedQuantityOf(dropId, userId) == null) {
            // 이미 소진된 드롭이면 대기열에 넣지도 않고 바로 SOLD_OUT을 알린다 - 그냥
            // enqueueOrFastAdmit을 호출해버리면 대기열에 등록됐다가 다음 폴링에야 소진을
            // 알게 되거나(체감 지연), ticketOf가 없어 resolveStatus가 NOT_IN_QUEUE로
            // 잘못 판정하는 함정이 있다(대기열에 한 번도 안 들어간 사람이 "나갔다"는 오표현).
            val reason = soldOutReason(
                total = confirmedSalesRepository.totalOf(dropId),
                closeAt = stockSnapshot?.closeAt,
                now = Instant.now(),
                confirmed = { confirmedSalesRepository.confirmedOf(dropId) },
            )
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
                dropId, userId, quantity, queueProperties.admission.ttlSeconds,
            )
        }
        return resolveStatus(dropId, userId, touchHeartbeat = false)
    }

    override fun status(dropId: String, userId: String): QueueStatusInfo =
        resolveStatus(dropId, userId, touchHeartbeat = true)

    override fun decide(dropId: String, userId: String, choice: DecisionChoice): QueueStatusInfo {
        when (choice) {
            // WAIT 확정 "그 순간"의 (grantableNow, optimisticMax) 두 값을 함께 기록한다 -
            // 이후 재질의 여부는 "이때와 비교해 둘 중 하나라도 바뀌었는가"로 판단한다
            // (resolveStatus의 재질의 게이트 참고). 그래서 여기서만 별도로 스냅샷을 한 번
            // 더 읽는다(폴링 hot path가 아니라 결정 액션 1회성 호출이라 왕복 1번 추가는
            // 무시할 만하다).
            //
            // 버그 이력(라이브 데모에서 재현, "취소해도 대기자가 못 깨어남"): 처음엔
            // optimisticMax(=total-confirmed)만 저장하고 그 값이 "나빠졌을 때만" 재질의했다.
            // 그런데 주문 *취소*(CANCELLED)는 confirmed가 아니라 reserved를 줄인다 -
            // grantableNow(=remaining-outstanding, remaining=total-reserved)만 움직이고
            // optimisticMax는 전혀 안 바뀐다. 그래서 대기자가 WAIT을 확정한 뒤 다른 사람이
            // 주문을 취소해 자리가 나도(grantableNow가 0에서 양수로 바뀌어도) optimisticMax
            // 기준 게이트는 "안 나빠졌다"고 보고 계속 조용히 재웠다 - 취소로 재고가 회복돼도
            // 대기자에게 영영 새 선택지(PARTIAL)가 안 뜨는 결함. grantableNow도 같이 추적해
            // 어느 한쪽이라도 바뀌면(더 나아졌든 나빠졌든) 다시 물어보게 고쳤다.
            DecisionChoice.WAIT -> {
                val snap = waitingQueueRepository.statusSnapshotOf(
                    dropId, userId, Instant.now(), touchHeartbeat = false,
                )
                waitingQueueRepository.markWaitConfirmed(
                    dropId, userId,
                    grantableNowAtConfirm = resolveGrantableNow(snap),
                    maxAtConfirm = resolveOptimisticMax(dropId, snap),
                )
            }
            DecisionChoice.GIVE_UP -> waitingQueueRepository.removeFromQueue(dropId, userId)
            // 실패(그 사이 재고가 완전히 사라짐)해도 별도 처리 불필요 - 아래 resolveStatus가
            // 최신 상태를 다시 계산해서 보여준다(예: WAITING 또는 다시 DECISION_REQUIRED).
            DecisionChoice.PARTIAL -> waitingQueueRepository.admitSingle(
                dropId, userId, queueProperties.admission.ttlSeconds,
            )
        }
        return resolveStatus(dropId, userId, touchHeartbeat = false)
    }

    /** `total - confirmed`(계속 기다렸을 때 도달 가능한 이론상 최댓값). total 미캐시면 null. */
    private fun resolveOptimisticMax(dropId: String, snap: QueueStatusSnapshot): Long? {
        val total = snap.total ?: confirmedSalesRepository.totalOf(dropId)
        return total?.let { it - snap.confirmed }
    }

    /** `max(remaining - outstanding, 0)`(지금 당장 PARTIAL로 받을 수 있는 양). remaining 미캐시면 0. */
    private fun resolveGrantableNow(snap: QueueStatusSnapshot): Long {
        val remaining = snap.remaining ?: 0
        return maxOf(remaining - snap.outstanding, 0)
    }

    /**
     * 진입 수량 상한 검증(서버 강제) - 프론트의 1~5 제한은 API 직접 호출로 우회 가능하고,
     * 엄격한 FIFO에서 상한 없는 수량은 rank 0 도달 시 대기열 전체를 무기한 정지시키는 벡터가
     * 되므로 반드시 서버에서 막는다. 두 겹으로 검증한다:
     * 1. 전역 안전망([QueueProperties.Entry.maxQuantity], env로 조정 가능)
     * 2. 드롭별 1인 구매 한도(queue 소유 `drop-meta` 부트스트랩 캐시의 `limitPerUser` - 이미
     *    읽고 있는 스냅샷이라 추가 조회 비용이 없다. 캐시 미워밍이면 전역 상한만 적용하고,
     *    최종 판정은 어차피 주문 시점에 product가 다시 한다 - 여기는 조기 차단으로 대기열
     *    낭비를 막는 층).
     */
    private fun validateQuantity(stockSnapshot: DropStockSnapshot?, quantity: Int) {
        val globalMax = queueProperties.entry.maxQuantity
        if (quantity > globalMax) {
            throw BusinessException(
                QueueErrorCode.QUANTITY_LIMIT_EXCEEDED,
                "요청 수량(${quantity}개)이 최대 허용 수량(${globalMax}개)을 초과했습니다.",
            )
        }
        val limitPerUser = stockSnapshot?.limitPerUser
        if (limitPerUser != null && quantity > limitPerUser) {
            throw BusinessException(
                QueueErrorCode.QUANTITY_LIMIT_EXCEEDED,
                "이 드롭의 1인 구매 한도(${limitPerUser}개)를 초과했습니다.",
            )
        }
    }

    private fun resolveStatus(dropId: String, userId: String, touchHeartbeat: Boolean): QueueStatusInfo {
        val pollIntervalMs = queueProperties.polling.intervalMs
        val now = Instant.now()

        // 폴링 hot path의 모든 읽기(입장권/순번/재고/outstanding/확정/결정상태)와 하트비트
        // 갱신을 원자적 Lua 한 번(1왕복)으로 묶는다 - 예전에는 이 지점에서 최대 9번의 순차
        // Redis 왕복이 발생해 "대기 인원 × 폴링 빈도"만큼 Redis와 서버 스레드풀을 태웠다.
        // 판정 로직은 그대로 이 계층에 남긴다(테스트 용이성 + 계층 분리 유지).
        val snap = waitingQueueRepository.statusSnapshotOf(dropId, userId, now, touchHeartbeat)

        if (snap.admittedQuantity != null) {
            return QueueStatusInfo(
                QueueStatus.READY,
                rank = null,
                totalWaiting = null,
                quantity = snap.admittedQuantity,
                grantableNow = null,
                optimisticMax = null,
                pollIntervalMs = pollIntervalMs,
            )
        }

        val rank = snap.rank
            ?: return QueueStatusInfo(
                QueueStatus.NOT_IN_QUEUE,
                rank = null,
                totalWaiting = null,
                quantity = null,
                grantableNow = null,
                optimisticMax = null,
                pollIntervalMs = pollIntervalMs,
            )
        val ticket = WaitingTicket(rank = rank, totalWaiting = snap.totalWaiting, quantity = snap.quantity ?: 1)

        // total은 원자 스냅샷에 캐시돼 있으면 그 값을, 없으면(부트스트랩 REST 미완료) 여기서
        // 한 번 더 시도한다 - soldOutReason과 아래 optimisticMax 계산이 같은 값을 재사용한다.
        val total = snap.total ?: confirmedSalesRepository.totalOf(dropId)
        val reason = soldOutReason(
            total = total,
            closeAt = snap.closeAt,
            now = now,
            confirmed = { snap.confirmed },
        )
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

        // 재고 캐시가 아직 없음(워밍 전) - 성급히 판단하지 않고 평범하게 대기시킨다.
        val remaining = snap.remaining
            ?: return waitingInfo(ticket, grantableNow = null, optimisticMax = null, pollIntervalMs)

        val available = remaining - snap.outstanding
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
        if (rank > 0) {
            return waitingInfo(ticket, grantableNow = null, optimisticMax = null, pollIntervalMs)
        }

        val grantableNow = resolveGrantableNow(snap)
        val optimisticMax = total?.let { it - snap.confirmed }

        val decision = snap.decision
        if (decision is DecisionState.WaitConfirmed) {
            // 버그 이력 1(라이브 데모에서 재현): 예전엔 "지금도 optimisticMax < quantity(=
            // SHORTFALL)인가"만 보고 재질의했다. 이러면 optimisticMax가 확정 이후 단 한
            // 번도 안 바뀌었어도(계속 같은 shortfall 상태를 유지 중이어도) 폴링할 때마다
            // (2초 간격) 매번 새로 DECISION_REQUIRED를 만들어, "기다림을 눌러도 아무 효과
            // 없이 같은 질문이 계속 반복되는" 것처럼 보였다 - WAIT 버튼을 눌러도 매번
            // decide(WAIT) → DECISION_REQUIRED로 돌아오는 무한 루프.
            //
            // 버그 이력 2(위 수정 직후, 역시 라이브 데모에서 재현): "확정 당시보다 optimisticMax가
            // 나빠졌을 때만" 재질의하도록 고쳤더니, 이번엔 반대 방향이 깨졌다 - 다른 유저의
            // 주문 *취소*(CANCELLED)로 재고가 회복돼도 대기자가 영영 못 깨어났다. 이유:
            // CANCELLED는 `reserved`를 줄여 grantableNow(=remaining-outstanding)를 올리지만,
            // `confirmed`는 안 건드리므로 optimisticMax(=total-confirmed)는 전혀 안 바뀐다.
            // optimisticMax만 보는 게이트는 "취소로 PARTIAL이 새로 가능해진" 신호 자체를
            // 볼 수가 없었다.
            //
            // 올바른 트리거는 "확정 당시보다 진짜로 더 나빠졌는가"가 아니라 "확정 당시와
            // 비교해 grantableNow/optimisticMax 둘 중 하나라도 바뀌었는가"다(나아졌든
            // 나빠졌든 - 어느 쪽이든 사용자가 마지막으로 본 것과 다른 새 정보이므로 다시
            // 물어볼 가치가 있다). 둘 다 확정 당시와 동일하면(=정말 아무것도 안 바뀐, 그냥
            // 같은 폴링이 반복된 것) 조용히 대기시킨다 - "한 번 기다림을 누르면, 상황이 그때와
            // 달라지지 않는 한 다시 안 물어본다"가 의도된 최종 동작이다. optimisticMax가
            // 확정 당시/지금 둘 다 null(총재고 미캐시)이었다면 그 항목은 "안 바뀐 것"으로
            // 본다(둘 다 모른다 → 비교 불가 → 변화 근거 없음, 보수적으로 조용히 대기).
            val optimisticMaxChanged = optimisticMax != decision.maxAtConfirm
            val grantableNowChanged = grantableNow != decision.grantableNowAtConfirm
            if (!optimisticMaxChanged && !grantableNowChanged) {
                return waitingInfo(ticket, grantableNow, optimisticMax, pollIntervalMs)
            }
        }

        // 무응답 이탈 처리용 마감 시각: 처음 묻는 순간 ASKED 마커를 원자 기록한다(이미 있으면
        // 기존 값 유지 - 폴링마다 마감이 밀리지 않게). 이 마감을 넘기면 sweep-decision.lua가
        // 대기열에서 제거한다 - 엄격한 FIFO에서 rank 0의 미결정은 큐 전체 정지이기 때문.
        // 단 WAIT을 명시 선택한 사람의 재질의(확정 당시와 상황이 달라짐)에는 마감을 걸지
        // 않는다(제거는 무응답자만).
        val decisionDeadlineEpochMs: Long? = when (decision) {
            is DecisionState.Asked -> decision.askedAtEpochMs + queueProperties.decision.timeoutMs
            is DecisionState.WaitConfirmed -> null
            null -> waitingQueueRepository.markAskedIfAbsent(dropId, userId, now)
                .takeIf { it >= 0 }
                ?.plus(queueProperties.decision.timeoutMs)
        }

        // 선택지는 서버가 권위 있게 내려준다(클라이언트는 이 목록의 버튼만 그림). grantableNow가
        // 0이면 PARTIAL은 "0개 부분구매"라는 무의미한(decide-partial.lua가 어차피 거부하는)
        // 선택지이므로 아예 제시하지 않는다 - PARTIAL 선택 → 0 발급 → 재질의로 이어지는
        // 무한 루프를 원천 차단한다.
        //
        // WAIT도 같은 원칙으로 걸러야 한다: optimisticMax(계속 기다렸을 때 도달 가능한 이론상
        // 최댓값)는 항상 grantableNow(지금 당장 받을 수 있는 양) 이상이다(total - confirmed
        // ≥ remaining - outstanding, confirmed ≤ reserved이고 outstanding ≥ 0이므로). 즉
        // optimisticMax == grantableNow인 경우는 "기다려도 지금보다 단 하나도 더 못 받는다"가
        // 수학적으로 확정된 상태 - 이때 WAIT을 제시하면 선택해도 상황이 절대 나아지지 않는
        // 빈 선택지를 주는 셈이다. optimisticMax가 아직 null(total 미워밍)이면 "더 받을 여지가
        // 없다"를 증명할 수 없으므로 보수적으로 WAIT을 유지한다.
        val availableChoices = buildList {
            if (optimisticMax == null || optimisticMax > grantableNow) add(DecisionChoice.WAIT.name)
            if (grantableNow > 0) add(DecisionChoice.PARTIAL.name)
            add(DecisionChoice.GIVE_UP.name)
        }

        // PARTIAL_OR_WAIT(처음 부족을 마주함) 또는 재질의(WAIT 확정 후 상황이 그때보다
        // 더 나빠짐 - 위 worsenedSinceConfirm 게이트를 통과한 경우만 여기 도달) - 클라이언트가
        // optimisticMax와 quantity를 비교해 어떤 다이얼로그를 보여줄지 스스로 판단한다.
        return QueueStatusInfo(
            QueueStatus.DECISION_REQUIRED,
            rank = ticket.rank,
            totalWaiting = ticket.totalWaiting,
            quantity = ticket.quantity,
            grantableNow = grantableNow,
            optimisticMax = optimisticMax,
            pollIntervalMs = pollIntervalMs,
            availableChoices = availableChoices,
            decisionDeadlineEpochMs = decisionDeadlineEpochMs,
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
     * 2. [SoldOutReason.STOCK_EXHAUSTED] - `confirmed >= total`일 때, 즉 총재고가 전부 확정
     *    판매됐을 때.
     *
     * **핵심 발견(수학적으로 증명됨, queue-remaining-sync 재설계 작업)**: 예전엔 이 판정을
     * `remaining > 0 → 소진 아님, 아니면 unconfirmedReserved(=reserved-confirmed) > 0 →
     * 소진 아님`으로 했다(reserved를 참조해야 했음). 그런데
     * `total - confirmed == remaining + unconfirmedReserved`(둘 다 항상 0 이상)이 항상
     * 성립하므로:
     *
     *   confirmed < total  ⟺  remaining > 0 또는 unconfirmedReserved > 0
     *
     * 즉 `confirmed < total`(=소진 아님) 하나만으로 예전 두 조건을 합친 것과 완전히 동일한
     * 결과를 낸다 - `reserved`를 이 판정에서 아예 뺄 수 있다(재고가 선점됐지만 아직 결제
     * 미완료라 반환 여지가 있는 "미확정 재고"가 남아있는 한, 그 결제가 실패해 재고가 돌아올
     * 가능성이 있으므로 성급히 포기시키지 않는다는 의도는 그대로 보존된다 - 단지 계산 경로만
     * 바뀌었을 뿐).
     *
     * `total` 조회가 실패하거나(product 응답 불가 등) 아직 캐시가 없으면 안전한 쪽으로
     * 저하한다 - SOLD_OUT을 내리지 않고 그냥 계속 대기시킨다(null 반환).
     *
     * 입장에 성공한 사람은 애초에 재고 인지형 admit.lua가 가용 재고 이하만 통과시키므로
     * 이 상태를 절대 보지 않는다("입장한 사람은 품절을 안 본다").
     */
    private fun soldOutReason(
        total: Long?,
        closeAt: Instant?,
        now: Instant,
        confirmed: () -> Long,
    ): SoldOutReason? {
        if (closeAt != null && !now.isBefore(closeAt)) {
            return SoldOutReason.CLOSED
        }

        val t = total ?: return null // 캐시 미워밍/부트스트랩 미완료 - 안전하게 저하(대기 유지)
        return if (confirmed() >= t) SoldOutReason.STOCK_EXHAUSTED else null
    }

    private enum class SoldOutReason {
        CLOSED,
        STOCK_EXHAUSTED,
    }
}
