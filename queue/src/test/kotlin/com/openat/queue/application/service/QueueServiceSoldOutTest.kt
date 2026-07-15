package com.openat.queue.application.service

import com.openat.queue.domain.model.DropStockSnapshot
import com.openat.queue.domain.model.QueueStatus
import com.openat.queue.domain.model.WaitingTicket
import com.openat.queue.domain.repository.ConfirmedSalesRepository
import com.openat.queue.domain.repository.StockRepository
import com.openat.queue.domain.repository.WaitingQueueRepository
import com.openat.queue.infrastructure.config.QueueProperties
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * `QueueService.enter()`의 소진(sold-out) 진입 가드를 검증한다(Mockito, Docker 불필요).
 * "선재고 선점" 모델 - `remaining`(직접 구매 가능)과 `total-confirmed`(미확정/반환 여지)를
 * 각각 어떻게 조합해야 하는지가 핵심이라 Redis 실제 동작보다 이 판정 로직 자체가 테스트 대상이다.
 */
@DisplayName("QueueService - 소진 진입 가드")
class QueueServiceSoldOutTest {

    private val dropId = "drop-1"
    private val userId = "user-1"

    @Test
    @DisplayName("직접 살 수 있는 재고도 없고 미확정 재고도 없으면 대기열에 넣지 않고 즉시 SOLD_OUT을 반환한다")
    fun enter_noRemainingNoUnconfirmed_returnsSoldOutWithoutEnqueue() {
        // total=10, remaining=0, confirmed=10 → unconfirmedReserved = (10-0)-10 = 0
        val service = serviceWith(remaining = 0, closeAt = null, total = 10, confirmed = 10)
        val waitingQueueRepository = service.waitingQueueRepository

        val result = service.instance.enter(dropId, userId, 2)

        assertThat(result.status).isEqualTo(QueueStatus.SOLD_OUT)
        assertThat(result.soldOutReason).isEqualTo("STOCK_EXHAUSTED")
        verify(waitingQueueRepository, never()).enqueueOrFastAdmit(any(), any(), any(), any(), any())
    }

    @Test
    @DisplayName("재고는 0이어도 미확정(반환 여지 있는) 재고가 남아있으면 정상적으로 대기열에 등록된다")
    fun enter_noRemainingButUnconfirmedExists_enqueuesNormally() {
        // total=10, remaining=0, confirmed=7 → unconfirmedReserved = (10-0)-7 = 3 > 0
        val service = serviceWith(remaining = 0, closeAt = null, total = 10, confirmed = 7)
        val waitingQueueRepository = service.waitingQueueRepository
        whenever(waitingQueueRepository.enqueueOrFastAdmit(any(), any(), any(), any(), any())).thenReturn(null)
        whenever(waitingQueueRepository.ticketOf(dropId, userId))
            .thenReturn(WaitingTicket(rank = 0, totalWaiting = 1, quantity = 2))

        val result = service.instance.enter(dropId, userId, 2)

        assertThat(result.status).isNotEqualTo(QueueStatus.SOLD_OUT)
        verify(waitingQueueRepository).enqueueOrFastAdmit(eq(dropId), eq(userId), eq(2), eq(service.ttlSeconds), any())
    }

    @Test
    @DisplayName("직접 구매 가능한 재고가 남아있으면 확정 수량 조회 없이도 소진이 아니다")
    fun enter_remainingAvailable_neverConsultsConfirmedSales() {
        val service = serviceWith(remaining = 5, closeAt = null, total = null, confirmed = 0)

        val result = service.instance.enter(dropId, userId, 1)

        assertThat(result.status).isNotEqualTo(QueueStatus.SOLD_OUT)
        // remaining>0 얼리 리턴 덕분에 total/confirmed는 아예 조회되지 않는다(불필요한 계산 생략 검증).
        verify(service.confirmedSalesRepository, never()).totalOf(any())
        verify(service.confirmedSalesRepository, never()).confirmedOf(any())
    }

    @Test
    @DisplayName("마감 시각이 지났으면 재고 계산과 무관하게 SOLD_OUT(CLOSED)이다")
    fun enter_closed_returnsSoldOutWithClosedReason() {
        val service = serviceWith(remaining = 100, closeAt = Instant.now().minusSeconds(60), total = null, confirmed = 0)
        val waitingQueueRepository = service.waitingQueueRepository

        val result = service.instance.enter(dropId, userId, 1)

        assertThat(result.status).isEqualTo(QueueStatus.SOLD_OUT)
        assertThat(result.soldOutReason).isEqualTo("CLOSED")
        verify(waitingQueueRepository, never()).enqueueOrFastAdmit(any(), any(), any(), any(), any())
    }

    private class ServiceFixture(
        val instance: QueueService,
        val waitingQueueRepository: WaitingQueueRepository,
        val confirmedSalesRepository: ConfirmedSalesRepository,
        val ttlSeconds: Long,
    )

    private fun serviceWith(remaining: Long, closeAt: Instant?, total: Long?, confirmed: Long): ServiceFixture {
        val waitingQueueRepository = mock<WaitingQueueRepository>()
        whenever(waitingQueueRepository.admittedQuantityOf(any(), any())).thenReturn(null)

        val stockRepository = mock<StockRepository>()
        whenever(stockRepository.snapshotOf(dropId)).thenReturn(DropStockSnapshot(remaining, closeAt))

        val confirmedSalesRepository = mock<ConfirmedSalesRepository>()
        whenever(confirmedSalesRepository.totalOf(dropId)).thenReturn(total)
        whenever(confirmedSalesRepository.confirmedOf(dropId)).thenReturn(confirmed)

        val queueProperties = QueueProperties()
        val service = QueueService(waitingQueueRepository, stockRepository, confirmedSalesRepository, queueProperties)
        return ServiceFixture(service, waitingQueueRepository, confirmedSalesRepository, queueProperties.admission.ttlSeconds)
    }
}
