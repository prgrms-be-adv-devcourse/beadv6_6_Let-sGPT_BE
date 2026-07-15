package com.openat.queue.application.service

import com.openat.queue.domain.model.DropStockSnapshot
import com.openat.queue.domain.model.QueueStatus
import com.openat.queue.domain.model.WaitingTicket
import com.openat.queue.domain.repository.ConfirmedSalesRepository
import com.openat.queue.domain.repository.StockRepository
import com.openat.queue.domain.repository.WaitingQueueRepository
import com.openat.queue.infrastructure.config.QueueProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * `QueueService.status()`(`resolveStatus`)의 엄격한 FIFO 공정성 게이트(`ticket.rank > 0`)를
 * 검증한다(Mockito, Docker 불필요) - admit.lua가 이제 맨 앞사람에서 멈추고 뒷사람은 아예
 * 살펴보지도 않으므로, DECISION_REQUIRED도 맨 앞(rank 0)인 사람에게만 물어야 사실과 일치한다.
 */
@DisplayName("QueueService - 대화형 결정(DECISION_REQUIRED) 공정성 게이트")
class QueueServiceDecisionTest {

    private val dropId = "drop-1"
    private val userId = "user-1"

    @Test
    @DisplayName("rank>0(아직 내 차례 아님)이면 재고가 부족해도 DECISION_REQUIRED 대신 WAITING을 반환한다")
    fun status_rankGreaterThanZero_returnsWaitingNotDecision() {
        val service = serviceWith(rank = 1, quantity = 5, remaining = 3, total = 10, confirmed = 2)

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.WAITING)
    }

    @Test
    @DisplayName("rank==0(지금 내 차례)이고 재고가 부족하면 DECISION_REQUIRED를 반환한다")
    fun status_rankZeroInsufficientAvailable_returnsDecisionRequired() {
        val service = serviceWith(rank = 0, quantity = 5, remaining = 3, total = 10, confirmed = 2)

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.DECISION_REQUIRED)
        assertThat(result.grantableNow).isEqualTo(3)
        assertThat(result.optimisticMax).isEqualTo(8)
    }

    @Test
    @DisplayName("rank==0이어도 가용 재고가 요청 수량을 채우면 결정 없이 WAITING이다(곧 정상 입장)")
    fun status_rankZeroButAvailableCoversQuantity_returnsWaitingWithoutDecision() {
        val service = serviceWith(rank = 0, quantity = 2, remaining = 5, total = 10, confirmed = 0)

        val result = service.status(dropId, userId)

        assertThat(result.status).isEqualTo(QueueStatus.WAITING)
    }

    private fun serviceWith(rank: Long, quantity: Int, remaining: Long, total: Long, confirmed: Long): QueueService {
        val waitingQueueRepository = mock<WaitingQueueRepository>()
        whenever(waitingQueueRepository.admittedQuantityOf(any(), any())).thenReturn(null)
        whenever(waitingQueueRepository.ticketOf(dropId, userId))
            .thenReturn(WaitingTicket(rank = rank, totalWaiting = rank + 1, quantity = quantity))
        whenever(waitingQueueRepository.outstandingOf(dropId)).thenReturn(0L)
        whenever(waitingQueueRepository.hasConfirmedWait(any(), any())).thenReturn(false)

        val stockRepository = mock<StockRepository>()
        whenever(stockRepository.snapshotOf(dropId)).thenReturn(DropStockSnapshot(remaining, null))

        val confirmedSalesRepository = mock<ConfirmedSalesRepository>()
        whenever(confirmedSalesRepository.totalOf(dropId)).thenReturn(total)
        whenever(confirmedSalesRepository.confirmedOf(dropId)).thenReturn(confirmed)

        return QueueService(waitingQueueRepository, stockRepository, confirmedSalesRepository, QueueProperties())
    }
}
