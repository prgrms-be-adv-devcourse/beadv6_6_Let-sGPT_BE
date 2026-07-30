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
import com.openat.queue.infrastructure.persistence.QueueEventPublisher
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 대기열 도메인 서비스. ZSET 조작(포트 호출)·입장 판정 로직을 여기 모아
 * 통신 방식(MVC/폴링, WebFlux/SSE)과 완전히 분리한다.
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
 *
 * feature/queue-remaining-sync(코루틴 전환): WebFlux+SSE 전환의 `Mono<T>` 체인을
 * `suspend fun`으로 다시 썼다. Redis 호출은 여전히 논블로킹(`ReactiveStringRedisTemplate`,
 * 리포지토리 구현체 참고)이라 Netty 이벤트루프를 계속 점유하지 않는 성질은 그대로 유지된다 -
 * 달라진 건 그 호출을 리액터 연산자(`flatMap`/`map`)로 엮는 대신 순차적인 코드로 표현한다는
 * 점뿐이다. 이 전환의 가장 큰 실질적 이득은 `Box<T>` 래퍼가 통째로 사라진 것 - Reactor는
 * `onNext(null)`을 금지해서 "없을 수도 있는 값"을 감싸는 컨테이너가 필요했지만, 코틀린의
 * suspend 함수는 그냥 nullable 타입(`Long?`, `DropStockSnapshot?`)을 직접 반환할 수 있다.
 */
@Service
class QueueService(
    private val waitingQueueRepository: WaitingQueueRepository,
    private val stockRepository: StockRepository,
    private val confirmedSalesRepository: ConfirmedSalesRepository,
    private val queueProperties: QueueProperties,
    private val queueEventPublisher: QueueEventPublisher,
) : EnterQueueUseCase, GetQueueStatusUseCase, AdmitWaitersUseCase, DecideQueueUseCase {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun admitBatch(dropId: String): List<AdmittedEntry> =
        waitingQueueRepository.admitBatch(
            dropId,
            queueProperties.admission.batchSize,
            queueProperties.admission.ttlSeconds,
        )

    override suspend fun enter(dropId: String, userId: String, quantity: Int): QueueStatusInfo {
        val stockSnapshot = stockRepository.snapshotOf(dropId)
        validateQuantity(stockSnapshot, quantity)

        val alreadyAdmitted = waitingQueueRepository.admittedQuantityOf(dropId, userId)
        if (alreadyAdmitted != null) {
            // 이미 미소진 입장권을 보유 중(READY)이면 재등록하지 않는다 - 재등록을 허용하면
            // 대기열에 다시 쌓였다가 다음 admit tick에서 같은 사용자가 또 admitBatch에
            // 뽑혀 outstanding이 중복 가산되는 회계 오류로 이어진다(admit.lua에도 동일
            // 목적의 방어가 있음 - 이 체크와 admit tick 사이 좁은 레이스까지 막는 이중 방어).
            return resolveStatus(dropId, userId, touchHeartbeat = false)
        }

        // 이미 소진된 드롭이면 대기열에 넣지도 않고 바로 SOLD_OUT을 알린다 - 그냥
        // enqueueOrFastAdmit을 호출해버리면 대기열에 등록됐다가 다음 폴링/스트림에야
        // 소진을 알게 되거나(체감 지연), ticketOf가 없어 resolveStatus가 NOT_IN_QUEUE로
        // 잘못 판정하는 함정이 있다(대기열에 한 번도 안 들어간 사람이 "나갔다"는 오표현).
        val reason = checkSoldOutBeforeEnqueue(dropId, stockSnapshot?.closeAt)
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

        // 정적 hot-drops 목록 없이 모든 드롭에 균일 적용 - 대기 중인 사람이 없고 재고가 즉시
        // 가용하면 대기열 없이 바로 입장권을 발급한다. 결과는 무시해도 안전하다: 아래
        // resolveStatus가 admittedQuantityOf/ticketOf를 다시 읽어 최신 상태를 그대로 반영하기
        // 때문(기존 enqueue 호출부와 동일).
        waitingQueueRepository.enqueueOrFastAdmit(dropId, userId, quantity, queueProperties.admission.ttlSeconds)
        return resolveStatus(dropId, userId, touchHeartbeat = false)
    }

    override suspend fun status(dropId: String, userId: String): QueueStatusInfo =
        resolveStatus(dropId, userId, touchHeartbeat = true)

    override suspend fun decide(dropId: String, userId: String, choice: DecisionChoice): QueueStatusInfo {
        when (choice) {
            // WAIT 확정 "그 순간"의 (grantableNow, optimisticMax) 두 값을 함께 기록한다 - 이후
            // 재질의 여부는 "이때와 비교해 둘 중 하나라도 바뀌었는가"로 판단한다(resolveStatus의
            // 재질의 게이트 참고). 그래서 여기서만 별도로 스냅샷을 한 번 더 읽는다(폴링/스트림
            // hot path가 아니라 결정 액션 1회성 호출이라 왕복 1번 추가는 무시할 만하다).
            DecisionChoice.WAIT -> {
                val snap = waitingQueueRepository.statusSnapshotOf(dropId, userId, Instant.now(), touchHeartbeat = false)
                val max = resolveOptimisticMax(dropId, snap)
                waitingQueueRepository.markWaitConfirmed(
                    dropId, userId,
                    grantableNowAtConfirm = resolveGrantableNow(snap),
                    maxAtConfirm = max,
                )
            }
            // 대기열 자리와 입장권은 서로 다른 상태라 둘 다 정리해야 한다 - 대기 중이면
            // 전자만, 이미 READY면 후자만 실제로 회수된다(둘 다 멱등이라 항상 같이 호출한다).
            //
            // 호출 순서가 의미를 갖는다: removeFromQueue가 반드시 먼저다. 뒤집으면 두 호출
            // 사이에 1초 주기 admit tick이 아직 대기열에 남아있는 이 사용자를 다시 입장시켜
            // 새 입장권을 발급할 수 있고, 그 입장권은 아무도 회수하지 않아 TTL까지 샌다.
            //
            // 두 Lua를 하나로 합치지 않는 이유: remove-from-queue.lua는 SSE 커넥션 끊김 시의
            // 즉시 회수(QueueStreamService.attemptReclaim)와 공유된다. 합치면 잠깐 끊긴
            // 사용자의 입장권까지 파괴돼 restore-admission.lua의 설계 의도와 어긋난다.
            DecisionChoice.GIVE_UP -> {
                val removed = waitingQueueRepository.removeFromQueue(dropId, userId)
                val releasedQty = waitingQueueRepository.releaseAdmission(
                    dropId, userId, queueProperties.admission.giveUpTombstoneTtlSeconds,
                )
                // 이 경로는 원래 로그/메트릭/이벤트가 전무해서 "정말로 나갔는지"를 서버에서
                // 확인할 방법이 없었다(Redis 키는 삭제가 정상이라 부재로는 증명이 안 됨).
                // removed=1 -> 대기 중이던 사람이 줄에서 빠짐 / releasedQty>0 -> READY였던
                // 사람이 입장권을 반납해 그만큼 재고가 다시 풀림.
                log.info(
                    "[queue-give-up] dropId={} userId={} removed={} releasedQty={}",
                    dropId, userId, removed, releasedQty,
                )
            }
            // 실패(그 사이 재고가 완전히 사라짐)해도 별도 처리 불필요 - 아래 resolveStatus가
            // 최신 상태를 다시 계산해서 보여준다(예: WAITING 또는 다시 DECISION_REQUIRED).
            DecisionChoice.PARTIAL -> waitingQueueRepository.admitSingle(dropId, userId, queueProperties.admission.ttlSeconds)
        }
        // WebFlux+SSE 전환분: 결정 직후 상태가 바뀌었을 수 있으니 구독 중인 다른 커넥션(들)에
        // 알린다. best-effort라 실패해도 이 요청 자체를 막지 않는다(QueueEventPublisher 참고).
        queueEventPublisher.publishChanged(dropId)
        return resolveStatus(dropId, userId, touchHeartbeat = false)
    }

    /** `total - confirmed`(계속 기다렸을 때 도달 가능한 이론상 최댓값). total 미캐시면 null. */
    private suspend fun resolveOptimisticMax(dropId: String, snap: QueueStatusSnapshot): Long? {
        val total = resolveTotal(dropId, snap.total) ?: return null
        return total - snap.confirmed
    }

    /** snap.total(원자 스냅샷에 캐시됨)이 있으면 그 값, 없으면(부트스트랩 REST 미완료) 한 번 더 시도. */
    private suspend fun resolveTotal(dropId: String, cachedTotal: Long?): Long? =
        cachedTotal ?: confirmedSalesRepository.totalOf(dropId)

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

    private suspend fun resolveStatus(dropId: String, userId: String, touchHeartbeat: Boolean): QueueStatusInfo {
        val pollIntervalMs = queueProperties.polling.intervalMs
        val now = Instant.now()

        // 폴링/스트림 hot path의 모든 읽기(입장권/순번/재고/outstanding/확정/결정상태)와
        // 하트비트 갱신을 원자적 Lua 한 번(1왕복)으로 묶는다 - 예전에는 이 지점에서 최대 9번의
        // 순차 Redis 왕복이 발생해 "대기 인원 × 폴링 빈도"만큼 Redis와 서버 스레드풀을 태웠다.
        val snap = waitingQueueRepository.statusSnapshotOf(dropId, userId, now, touchHeartbeat)
        return resolveFromSnapshot(dropId, userId, snap, pollIntervalMs)
    }

    private suspend fun resolveFromSnapshot(
        dropId: String,
        userId: String,
        snap: QueueStatusSnapshot,
        pollIntervalMs: Long,
    ): QueueStatusInfo {
        if (snap.admittedQuantity != null) {
            return QueueStatusInfo(
                QueueStatus.READY,
                rank = null, totalWaiting = null, quantity = snap.admittedQuantity,
                grantableNow = null, optimisticMax = null, pollIntervalMs = pollIntervalMs,
            )
        }

        val rank = snap.rank ?: return QueueStatusInfo(
            QueueStatus.NOT_IN_QUEUE,
            rank = null, totalWaiting = null, quantity = null,
            grantableNow = null, optimisticMax = null, pollIntervalMs = pollIntervalMs,
        )
        val ticket = WaitingTicket(rank = rank, totalWaiting = snap.totalWaiting, quantity = snap.quantity ?: 1)

        // total은 원자 스냅샷에 캐시돼 있으면 그 값을, 없으면(부트스트랩 REST 미완료) 여기서
        // 한 번 더 시도한다 - soldOutReason과 아래 optimisticMax 계산이 같은 값을 재사용한다.
        val total = resolveTotal(dropId, snap.total)
        val reason = soldOutReasonSync(total, snap.closeAt, Instant.now(), snap.confirmed)
        if (reason != null) {
            return QueueStatusInfo(
                QueueStatus.SOLD_OUT,
                rank = null, totalWaiting = null, quantity = ticket.quantity,
                grantableNow = null, optimisticMax = null, pollIntervalMs = pollIntervalMs,
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
        // 넣어 이 앱 레이어 체크와 그 사이의 레이스까지 막는다(이중 방어).
        if (rank > 0) {
            return waitingInfo(ticket, grantableNow = null, optimisticMax = null, pollIntervalMs)
        }

        val grantableNow = resolveGrantableNow(snap)
        val optimisticMax = total?.let { it - snap.confirmed }

        val decision = snap.decision
        if (decision is DecisionState.WaitConfirmed) {
            // "확정 당시와 비교해 grantableNow/optimisticMax 둘 중 하나라도 바뀌었는가"만
            // 재질의 트리거로 쓴다(나아졌든 나빠졌든) - 버그 이력은 원본 QueueService(stage1)
            // 주석 참고. 둘 다 안 바뀌었으면 조용히 대기시킨다.
            val optimisticMaxChanged = optimisticMax != decision.maxAtConfirm
            val grantableNowChanged = grantableNow != decision.grantableNowAtConfirm
            if (!optimisticMaxChanged && !grantableNowChanged) {
                return waitingInfo(ticket, grantableNow, optimisticMax, pollIntervalMs)
            }
        }

        // 무응답 이탈 처리용 마감 시각: 처음 묻는 순간 ASKED 마커를 원자 기록한다(이미 있으면
        // 기존 값 유지). WAIT을 명시 선택한 사람의 재질의에는 마감을 걸지 않는다(제거는 무응답자만).
        val deadline: Long? = when (decision) {
            is DecisionState.Asked -> decision.askedAtEpochMs + queueProperties.decision.timeoutMs
            is DecisionState.WaitConfirmed -> null
            null -> waitingQueueRepository.markAskedIfAbsent(dropId, userId, Instant.now())
                .takeIf { it >= 0 }?.plus(queueProperties.decision.timeoutMs)
        }

        // 선택지는 서버가 권위 있게 내려준다. grantableNow가 0이면 PARTIAL은 "0개
        // 부분구매"라는 무의미한 선택지이므로 제시하지 않는다(무한 재질의 루프 차단).
        // optimisticMax == grantableNow면 "기다려도 더 못 받는다"가 확정된 것이므로
        // WAIT도 제외한다(수학적 근거는 원본 QueueService 주석 참고).
        val availableChoices = buildList {
            if (optimisticMax == null || optimisticMax > grantableNow) add(DecisionChoice.WAIT.name)
            if (grantableNow > 0) add(DecisionChoice.PARTIAL.name)
            add(DecisionChoice.GIVE_UP.name)
        }

        return QueueStatusInfo(
            QueueStatus.DECISION_REQUIRED,
            rank = ticket.rank,
            totalWaiting = ticket.totalWaiting,
            quantity = ticket.quantity,
            grantableNow = grantableNow,
            optimisticMax = optimisticMax,
            pollIntervalMs = pollIntervalMs,
            availableChoices = availableChoices,
            decisionDeadlineEpochMs = deadline,
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
     * enter()에서 아직 대기열에 없는 사용자를 등록하기 전, 이미 더 이상 입장 기회가 없는지
     * 확인한다("선재고 선점" 모델 기준 - 자세한 판정 근거는 [soldOutReasonSync] 참고).
     * @return 사유가 있으면 그 값, 없으면(=매진 아님, 또는 total 미캐시라 판단 불가) null
     */
    private suspend fun checkSoldOutBeforeEnqueue(dropId: String, closeAt: Instant?): SoldOutReason? {
        val now = Instant.now()
        if (closeAt != null && !now.isBefore(closeAt)) {
            return SoldOutReason.CLOSED
        }
        // total이 없으면(부트스트랩 미완료) confirmedOf까지 갈 것도 없이 그대로 null을 반환한다 -
        // "판단 불가 → 대기 유지"와 동일한 안전 저하.
        val total = confirmedSalesRepository.totalOf(dropId) ?: return null
        val confirmed = confirmedSalesRepository.confirmedOf(dropId)
        return if (confirmed >= total) SoldOutReason.STOCK_EXHAUSTED else null
    }

    /**
     * "더 이상 입장 기회가 없다"를 통지하는 사유. 선재고 선점 모델(주문 *생성* 시점에 이미
     * remaining이 차감되고, 결제까지 끝나야 confirmed로 확정됨) 기준으로:
     *
     * 1. [SoldOutReason.CLOSED] - 드롭이 마감(closeAt 경과).
     * 2. [SoldOutReason.STOCK_EXHAUSTED] - `confirmed >= total`일 때, 즉 총재고가 전부 확정
     *    판매됐을 때.
     *
     * `total` 조회가 실패하거나(product 응답 불가 등) 아직 캐시가 없으면(total==null) 안전한
     * 쪽으로 저하한다 - SOLD_OUT을 내리지 않고 그냥 계속 대기시킨다(null 반환).
     *
     * 입장에 성공한 사람은 애초에 재고 인지형 admit.lua가 가용 재고 이하만 통과시키므로
     * 이 상태를 절대 보지 않는다("입장한 사람은 품절을 안 본다").
     */
    private fun soldOutReasonSync(total: Long?, closeAt: Instant?, now: Instant, confirmed: Long): SoldOutReason? {
        if (closeAt != null && !now.isBefore(closeAt)) {
            return SoldOutReason.CLOSED
        }
        val t = total ?: return null
        return if (confirmed >= t) SoldOutReason.STOCK_EXHAUSTED else null
    }

    private enum class SoldOutReason {
        CLOSED,
        STOCK_EXHAUSTED,
    }
}
