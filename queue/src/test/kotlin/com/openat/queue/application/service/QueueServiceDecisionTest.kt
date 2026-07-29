package com.openat.queue.application.service

import com.openat.queue.domain.model.DecisionChoice
import com.openat.queue.domain.model.DecisionState
import com.openat.queue.domain.model.QueueStatus
import com.openat.queue.domain.model.QueueStatusSnapshot
import com.openat.queue.domain.repository.ConfirmedSalesRepository
import com.openat.queue.domain.repository.StockRepository
import com.openat.queue.domain.repository.WaitingQueueRepository
import com.openat.queue.infrastructure.config.QueueProperties
import com.openat.queue.infrastructure.persistence.QueueEventPublisher
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * `QueueService.status()`(`resolveStatus`)의 엄격한 FIFO 공정성 게이트(`rank > 0`)와
 * 대화형 결정의 선택지/무응답 마감 정책을 검증한다(Mockito, Docker 불필요).
 * 읽기는 [WaitingQueueRepository.statusSnapshotOf]가 원자 스냅샷 하나로 제공하고(1왕복),
 * 판정 로직은 이 계층에 남아 있으므로 스냅샷 값만 바꿔가며 순수하게 검증할 수 있다.
 *
 * 소진(SOLD_OUT) 판정은 `confirmed < total`로만 결정된다(핵심 발견: 예전 `remaining>0 또는
 * reserved-confirmed>0` 판정과 수학적으로 항상 동치임이 증명됨 - queue-remaining-sync
 * 재설계 작업 참고). 이 테스트들의 `total`/`confirmed` 조합은 전부 `confirmed < total`이라
 * SOLD_OUT으로 새지 않는다 - `remaining`은 admission(available/grantableNow) 판정에만 쓰인다.
 *
 * feature/queue-remaining-sync(코루틴 전환): [QueueService]가 `suspend fun`이므로 각 테스트를
 * `runBlocking { }`으로 감싸고, mock의 스텁도 `Mono.just(...)` 대신 값을 직접 반환한다.
 * `markWaitConfirmed`(Unit 반환)처럼 결과값을 쓰지 않고 그냥 호출만 하는 자리는 더 이상 빈
 * Mono를 명시적으로 스텁하지 않아도 된다 - Reactor 버전은 `.then()`이 null Mono를 받으면
 * 그 자리에서 NPE가 났지만, suspend 함수는 반환값을 실제로 쓰지 않으면 스텁 여부가 문제되지
 * 않는다(Box<T> 래퍼가 사라진 것과 같은 이유).
 */
@DisplayName("QueueService - 대화형 결정(DECISION_REQUIRED) 공정성 게이트")
class QueueServiceDecisionTest {

    private val dropId = "drop-1"
    private val userId = "user-1"

    @Test
    @DisplayName("rank>0(아직 내 차례 아님)이면 재고가 부족해도 DECISION_REQUIRED 대신 WAITING을 반환한다")
    fun status_rankGreaterThanZero_returnsWaitingNotDecision() = runBlocking<Unit> {
        val service = serviceWith(rank = 1, quantity = 5, remaining = 3, total = 10, confirmed = 2)

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.WAITING)
    }

    @Test
    @DisplayName("rank==0(지금 내 차례)이고 재고가 부족하면 DECISION_REQUIRED를 반환한다")
    fun status_rankZeroInsufficientAvailable_returnsDecisionRequired() = runBlocking<Unit> {
        val service = serviceWith(rank = 0, quantity = 5, remaining = 3, total = 10, confirmed = 2)

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.DECISION_REQUIRED)
        assertThat(result.grantableNow).isEqualTo(3)
        assertThat(result.optimisticMax).isEqualTo(8)
    }

    @Test
    @DisplayName("rank==0이어도 가용 재고가 요청 수량을 채우면 결정 없이 WAITING이다(곧 정상 입장)")
    fun status_rankZeroButAvailableCoversQuantity_returnsWaitingWithoutDecision() = runBlocking<Unit> {
        val service = serviceWith(rank = 0, quantity = 2, remaining = 5, total = 10, confirmed = 0)

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.WAITING)
    }

    @Test
    @DisplayName("grantableNow>0이면 선택지에 PARTIAL이 포함되고, 무응답 마감 시각(askedAt+timeout)이 내려간다")
    fun status_grantablePositive_offersPartialWithDeadline() = runBlocking<Unit> {
        val service = serviceWith(rank = 0, quantity = 5, remaining = 3, total = 10, confirmed = 2)

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.DECISION_REQUIRED)
        assertThat(result.availableChoices).containsExactly("WAIT", "PARTIAL", "GIVE_UP")
        assertThat(result.decisionDeadlineEpochMs)
            .isEqualTo(ASKED_AT_MS + QueueProperties().decision.timeoutMs)
    }

    @Test
    @DisplayName("grantableNow==0이면 PARTIAL을 선택지에서 제외한다('0개 부분구매' 무한 재질의 루프 차단)")
    fun status_grantableZero_excludesPartialChoice() = runBlocking<Unit> {
        // remaining=0이지만 confirmed(2) < total(10)이라 SOLD_OUT은 아님 - 결정을 묻되 지금
        // 줄 수 있는 게 0이므로 PARTIAL은 무의미하다(decide-partial.lua도 어차피 거부).
        val service = serviceWith(rank = 0, quantity = 5, remaining = 0, total = 10, confirmed = 2)

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.DECISION_REQUIRED)
        assertThat(result.grantableNow).isEqualTo(0)
        assertThat(result.availableChoices).containsExactly("WAIT", "GIVE_UP")
    }

    @Test
    @DisplayName("WAIT을 이미 확정했고 grantableNow/optimisticMax가 확정 당시와 같으면(변화 없음) WAITING을 유지한다")
    fun status_waitConfirmedWithoutShortfall_staysWaiting() = runBlocking<Unit> {
        // 지금: grantableNow = 3 - 0 = 3, optimisticMax = 10 - 2 = 8. 확정 당시도 동일 - 변화 없음.
        val service = serviceWith(
            rank = 0, quantity = 5, remaining = 3, total = 10, confirmed = 2,
            decision = DecisionState.WaitConfirmed(grantableNowAtConfirm = 3, maxAtConfirm = 8),
        )

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.WAITING)
    }

    @Test
    @DisplayName("WAIT 확정자도 확정 당시보다 optimisticMax가 바뀌었으면 재질의하되, 무응답 마감은 걸지 않는다(제거는 무응답자만)")
    fun status_waitConfirmedShortfall_reasksWithoutDeadline() = runBlocking<Unit> {
        // 지금: grantableNow = 3 - 0 = 3(확정 당시와 동일), optimisticMax = 4 - 0 = 4
        // (확정 당시 6에서 바뀜) - optimisticMax만 바뀌어도 재질의된다.
        val service = serviceWith(
            rank = 0, quantity = 5, remaining = 3, total = 4, confirmed = 0,
            decision = DecisionState.WaitConfirmed(grantableNowAtConfirm = 3, maxAtConfirm = 6),
        )

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.DECISION_REQUIRED)
        assertThat(result.decisionDeadlineEpochMs).isNull()
    }

    @Test
    @DisplayName(
        "[회귀 1] WAIT 확정 후 grantableNow/optimisticMax가 둘 다 그대로면(shortfall이 계속돼도) 재질의하지 않는다 " +
            "- 라이브 데모에서 재현된 버그: '기다림을 눌러도 폴링마다 같은 질문이 반복되는' 무한 루프",
    )
    fun status_waitConfirmedUnchangedShortfall_staysQuietWithoutReasking() = runBlocking<Unit> {
        // 지금: grantableNow = 1 - 0 = 1, optimisticMax = 1 - 0 = 1. 확정 당시도 둘 다 동일 -
        // quantity(5)에는 여전히 못 미치지만(옛 로직이면 "아직도 shortfall"이라며 매 폴링마다
        // 재질의했을 상황) 확정 당시와 비교해 하나도 안 바뀌었으므로 조용히 대기해야 한다.
        val service = serviceWith(
            rank = 0, quantity = 5, remaining = 1, total = 1, confirmed = 0,
            decision = DecisionState.WaitConfirmed(grantableNowAtConfirm = 1, maxAtConfirm = 1),
        )

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.WAITING)
    }

    @Test
    @DisplayName(
        "[회귀 2] WAIT 확정 후 다른 유저의 주문 취소로 재고가 회복되면(grantableNow만 바뀌고 optimisticMax는 그대로여도) 재질의한다 " +
            "- 라이브 데모에서 재현된 버그: optimisticMax만 보는 게이트가 취소로 인한 재고 회복 신호를 놓쳐 대기자가 영영 못 깨어남",
    )
    fun status_waitConfirmedCancellationFreesStock_reasksEvenThoughOptimisticMaxUnchanged() = runBlocking<Unit> {
        // 확정 당시: grantableNow=0(아무것도 못 받았음), optimisticMax=1(그때도 이미 1).
        // 그 뒤 다른 유저가 주문을 취소해 reserved가 줄고 remaining이 늘어 지금 grantableNow=1이
        // 됐다 - 하지만 confirmed는 취소로 안 바뀌므로 optimisticMax는 여전히 1(그대로).
        // optimisticMax만 보는 옛 게이트는 이 변화를 "안 바뀜"으로 오판해 영원히 재질의를
        // 안 하는 결함이 있었다 - grantableNow도 같이 봐야 잡힌다.
        val service = serviceWith(
            rank = 0, quantity = 5, remaining = 1, total = 1, confirmed = 0,
            decision = DecisionState.WaitConfirmed(grantableNowAtConfirm = 0, maxAtConfirm = 1),
        )

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.DECISION_REQUIRED)
        assertThat(result.grantableNow).isEqualTo(1)
        assertThat(result.optimisticMax).isEqualTo(1)
        // optimisticMax(1) == grantableNow(1)이므로 지난 세션에 고친 규칙대로 WAIT은 빈
        // 선택지로 다시 제외되고 PARTIAL/GIVE_UP만 남는다(두 수정이 합쳐진 결과).
        assertThat(result.availableChoices).containsExactly("PARTIAL", "GIVE_UP")
    }

    @Test
    @DisplayName("WAIT 확정 당시부터 지금까지 계속 optimisticMax를 모르면(둘 다 null) 조용히 대기한다")
    fun status_waitConfirmedStillUnknownMax_staysQuietConservatively() = runBlocking<Unit> {
        val service = serviceWith(
            rank = 0, quantity = 5, remaining = 3, total = null, confirmed = 2,
            decision = DecisionState.WaitConfirmed(grantableNowAtConfirm = 3, maxAtConfirm = null),
        )

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.WAITING)
    }

    @Test
    @DisplayName("decide(WAIT)는 그 순간의 (grantableNow, optimisticMax)를 markWaitConfirmed에 함께 기록한다")
    fun decide_wait_recordsGrantableNowAndOptimisticMaxAtConfirmMoment() = runBlocking<Unit> {
        // remaining=3, total=10, confirmed=2 → 이 순간 grantableNow=3, optimisticMax=8.
        val service = serviceWith(rank = 0, quantity = 5, remaining = 3, total = 10, confirmed = 2)

        service.decide(dropId, userId, DecisionChoice.WAIT)

        verify(waitingQueueRepositoryMock).markWaitConfirmed(dropId, userId, 3L, 8L)
    }

    @Test
    @DisplayName("optimisticMax == grantableNow(기다려도 절대 더 못 받음이 확정)이면 WAIT을 선택지에서 제외한다")
    fun status_optimisticMaxEqualsGrantableNow_excludesWaitChoice() = runBlocking<Unit> {
        // 실사용 재현: 10명이 2개씩 요청, 총재고 5, 2명(4개)이 이미 결제 확정(confirmed=4).
        // grantableNow = remaining(1) - outstanding(0) = 1, optimisticMax = total(5) - confirmed(4) = 1.
        // 두 값이 같으므로 "계속 기다려도 지금 받을 수 있는 1개보다 더 못 받는다"가 확정된
        // 상태 - WAIT은 빈 선택지이므로 PARTIAL/GIVE_UP만 남아야 한다.
        val service = serviceWith(rank = 0, quantity = 2, remaining = 1, total = 5, confirmed = 4)

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.DECISION_REQUIRED)
        assertThat(result.grantableNow).isEqualTo(1)
        assertThat(result.optimisticMax).isEqualTo(1)
        assertThat(result.availableChoices).containsExactly("PARTIAL", "GIVE_UP")
    }

    @Test
    @DisplayName("optimisticMax > grantableNow(기다리면 더 받을 여지가 남음)이면 WAIT을 그대로 제시한다")
    fun status_optimisticMaxGreaterThanGrantableNow_keepsWaitChoice() = runBlocking<Unit> {
        val service = serviceWith(rank = 0, quantity = 5, remaining = 3, total = 10, confirmed = 2)

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.DECISION_REQUIRED)
        assertThat(result.grantableNow).isEqualTo(3)
        assertThat(result.optimisticMax).isEqualTo(8)
        assertThat(result.availableChoices).containsExactly("WAIT", "PARTIAL", "GIVE_UP")
    }

    @Test
    @DisplayName("optimisticMax를 아직 알 수 없으면(total 미워밍) '더 받을 여지 없음'을 증명할 수 없으므로 WAIT을 보수적으로 유지한다")
    fun status_totalNotCachedYet_keepsWaitChoiceConservatively() = runBlocking<Unit> {
        val service = serviceWith(rank = 0, quantity = 5, remaining = 3, total = null, confirmed = 2)

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.DECISION_REQUIRED)
        assertThat(result.optimisticMax).isNull()
        assertThat(result.availableChoices).containsExactly("WAIT", "PARTIAL", "GIVE_UP")
    }

    @Test
    @DisplayName("WAIT 확정자가 재질의를 받아도(확정 당시보다 optimisticMax가 바뀜) optimisticMax == grantableNow면 WAIT을 다시 주지 않는다(최종 결정 유도)")
    fun status_waitConfirmedShortfallWithNoUpside_excludesWaitChoiceOnReask() = runBlocking<Unit> {
        // 지금: grantableNow = 1 - 0 = 1(확정 당시와 동일), optimisticMax = 1 - 0 = 1
        // (확정 당시 2에서 바뀜) - optimisticMax가 바뀌어서 재질의된다. 게다가
        // grantableNow(1) == optimisticMax(1)라 재질의 시에도 WAIT은 빈 선택지고,
        // grantableNow>0이므로 PARTIAL만 살아남는다(GIVE_UP은 항상 포함).
        val service = serviceWith(
            rank = 0, quantity = 5, remaining = 1, total = 1, confirmed = 0,
            decision = DecisionState.WaitConfirmed(grantableNowAtConfirm = 1, maxAtConfirm = 2),
        )

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.DECISION_REQUIRED)
        assertThat(result.availableChoices).containsExactly("PARTIAL", "GIVE_UP")
    }

    @Test
    @DisplayName("이미 입장권을 받은(READY) 사용자가 포기하면 대기열 제거뿐 아니라 입장권 반납도 함께 호출한다")
    fun decide_giveUpWhenReady_alsoReleasesAdmission() = runBlocking<Unit> {
        // 회귀 방지: 예전엔 GIVE_UP이 removeFromQueue만 호출해서, 이미 READY인 사용자는
        // admission 키가 남아 계속 READY로 보이고 outstanding도 TTL(기본 180초)까지 묶였다.
        val repository = mock<WaitingQueueRepository>()
        whenever(repository.statusSnapshotOf(eq(dropId), eq(userId), any(), any()))
            .thenReturn(
                QueueStatusSnapshot(
                    admittedQuantity = 3, // READY 상태
                    rank = null,
                    totalWaiting = 0,
                    quantity = null,
                    remaining = null,
                    closeAt = null,
                    outstanding = 3,
                    confirmed = 0,
                    total = 10,
                    decision = null,
                    reserved = 0,
                ),
            )
        whenever(repository.removeFromQueue(dropId, userId)).thenReturn(0) // 대기열엔 이미 없음
        whenever(repository.releaseAdmission(dropId, userId)).thenReturn(3)
        val service = QueueService(
            repository, mock(), mock(), QueueProperties(), mock(),
        )

        service.decide(dropId, userId, DecisionChoice.GIVE_UP)

        verify(repository).removeFromQueue(dropId, userId)
        verify(repository).releaseAdmission(dropId, userId)
    }

    private lateinit var waitingQueueRepositoryMock: WaitingQueueRepository

    private suspend fun serviceWith(
        rank: Long,
        quantity: Int,
        remaining: Long,
        total: Long?,
        confirmed: Long,
        decision: DecisionState? = null,
    ): QueueService {
        val waitingQueueRepository = mock<WaitingQueueRepository>()
        waitingQueueRepositoryMock = waitingQueueRepository
        whenever(waitingQueueRepository.statusSnapshotOf(eq(dropId), eq(userId), any(), any()))
            .thenReturn(
                QueueStatusSnapshot(
                    admittedQuantity = null,
                    rank = rank,
                    totalWaiting = rank + 1,
                    quantity = quantity,
                    remaining = remaining,
                    closeAt = null,
                    outstanding = 0,
                    confirmed = confirmed,
                    total = total,
                    decision = decision,
                    reserved = 0,
                ),
            )
        whenever(waitingQueueRepository.markAskedIfAbsent(eq(dropId), eq(userId), any()))
            .thenReturn(ASKED_AT_MS)

        val stockRepository = mock<StockRepository>()
        val confirmedSalesRepository = mock<ConfirmedSalesRepository>()
        // total이 null(미워밍)인 케이스를 위한 폴백 경로(resolveTotal의 `snap.total ?:
        // confirmedSalesRepository.totalOf(dropId)`)도 명시적으로 null을 반환하도록 스텁한다.
        whenever(confirmedSalesRepository.totalOf(eq(dropId))).thenReturn(total)

        val queueEventPublisher = mock<QueueEventPublisher>()

        return QueueService(
            waitingQueueRepository, stockRepository, confirmedSalesRepository, QueueProperties(), queueEventPublisher,
        )
    }

    companion object {
        private const val ASKED_AT_MS = 1_700_000_000_000L
    }
}
