package com.openat.queue.application.service

import com.openat.common.exception.BusinessException
import com.openat.queue.domain.model.DropStockSnapshot
import com.openat.queue.domain.model.QueueStatus
import com.openat.queue.domain.model.QueueStatusSnapshot
import com.openat.queue.domain.repository.ConfirmedSalesRepository
import com.openat.queue.domain.repository.StockRepository
import com.openat.queue.domain.repository.WaitingQueueRepository
import com.openat.queue.infrastructure.config.QueueProperties
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * `QueueService.enter()`의 소진(sold-out) 진입 가드와 수량 상한 검증을 확인한다(Mockito, Docker 불필요).
 *
 * 핵심 발견(queue-remaining-sync 재설계 작업)으로 판정식이 `remaining>0 또는
 * unconfirmedReserved(=reserved-confirmed)>0`에서 `confirmed < total`로 단순화됐다 - 두 식이
 * 수학적으로 항상 동치임이 증명됨(`total - confirmed == remaining + unconfirmedReserved`,
 * 둘 다 항상 0 이상). 이 테스트는 이제 `total`/`confirmed` 조합으로 SOLD_OUT 여부를 검증한다.
 * `remaining`은(대기열 등록/즉시입장 분기에는 여전히 쓰이지만) 더 이상 이 소진 판정 자체에는
 * 관여하지 않는다.
 */
@DisplayName("QueueService - 소진 진입 가드")
class QueueServiceSoldOutTest {

    private val dropId = "drop-1"
    private val userId = "user-1"

    @Test
    @DisplayName("확정 수량이 총재고에 도달하면 대기열에 넣지 않고 즉시 SOLD_OUT을 반환한다")
    fun enter_confirmedReachesTotal_returnsSoldOutWithoutEnqueue() {
        // total=10, confirmed=10 → confirmed >= total
        val service = serviceWith(remaining = 0, closeAt = null, total = 10, confirmed = 10)
        val waitingQueueRepository = service.waitingQueueRepository

        val result = service.instance.enter(dropId, userId, 2)

        assertThat(result.status).isEqualTo(QueueStatus.SOLD_OUT)
        assertThat(result.soldOutReason).isEqualTo("STOCK_EXHAUSTED")
        verify(waitingQueueRepository, never()).enqueueOrFastAdmit(any(), any(), any(), any())
    }

    @Test
    @DisplayName("확정 수량이 총재고보다 적으면(아직 미확정 여지가 있으면) 정상적으로 대기열에 등록된다")
    fun enter_confirmedBelowTotal_enqueuesNormally() {
        // total=10, confirmed=7 → confirmed < total
        val service = serviceWith(remaining = 0, closeAt = null, total = 10, confirmed = 7, waitingQuantity = 2)
        val waitingQueueRepository = service.waitingQueueRepository
        whenever(waitingQueueRepository.enqueueOrFastAdmit(any(), any(), any(), any())).thenReturn(null)

        val result = service.instance.enter(dropId, userId, 2)

        assertThat(result.status).isNotEqualTo(QueueStatus.SOLD_OUT)
        verify(waitingQueueRepository).enqueueOrFastAdmit(eq(dropId), eq(userId), eq(2), eq(service.ttlSeconds))
    }

    @Test
    @DisplayName("total 캐시가 아직 없으면(부트스트랩 미완료) confirmed 조회 없이 안전하게 대기시킨다")
    fun enter_totalNotCachedYet_neverConsultsConfirmedAndDoesNotSoldOut() {
        val service = serviceWith(remaining = 5, closeAt = null, total = null, confirmed = 0)

        val result = service.instance.enter(dropId, userId, 1)

        assertThat(result.status).isNotEqualTo(QueueStatus.SOLD_OUT)
        // total이 null이면 얼리 리턴이라 confirmed는 아예 조회되지 않는다(불필요한 왕복 생략 검증).
        verify(service.confirmedSalesRepository, never()).confirmedOf(any())
    }

    @Test
    @DisplayName("마감 시각이 지났으면 재고 계산과 무관하게 SOLD_OUT(CLOSED)이다")
    fun enter_closed_returnsSoldOutWithClosedReason() {
        val service = serviceWith(remaining = 100, closeAt = Instant.now().minusSeconds(60), total = 100, confirmed = 0)
        val waitingQueueRepository = service.waitingQueueRepository

        val result = service.instance.enter(dropId, userId, 1)

        assertThat(result.status).isEqualTo(QueueStatus.SOLD_OUT)
        assertThat(result.soldOutReason).isEqualTo("CLOSED")
        verify(waitingQueueRepository, never()).enqueueOrFastAdmit(any(), any(), any(), any())
    }

    @Test
    @DisplayName("전역 상한(기본 5)을 초과하는 수량은 대기열에 넣지 않고 즉시 거부한다(서버 강제)")
    fun enter_quantityOverGlobalMax_rejectsWithoutEnqueue() {
        val service = serviceWith(remaining = 100, closeAt = null, total = 100, confirmed = 0)
        val waitingQueueRepository = service.waitingQueueRepository

        assertThatThrownBy { service.instance.enter(dropId, userId, 999999) }
            .isInstanceOf(BusinessException::class.java)
        verify(waitingQueueRepository, never()).enqueueOrFastAdmit(any(), any(), any(), any())
    }

    @Test
    @DisplayName("드롭별 1인 구매 한도(limitPerUser)가 전역 상한보다 작으면 그것도 강제한다")
    fun enter_quantityOverLimitPerUser_rejects() {
        val service = serviceWith(remaining = 100, closeAt = null, total = 100, confirmed = 0, limitPerUser = 2)

        assertThatThrownBy { service.instance.enter(dropId, userId, 3) }
            .isInstanceOf(BusinessException::class.java)
    }

    private class ServiceFixture(
        val instance: QueueService,
        val waitingQueueRepository: WaitingQueueRepository,
        val confirmedSalesRepository: ConfirmedSalesRepository,
        val ttlSeconds: Long,
    )

    private fun serviceWith(
        remaining: Long,
        closeAt: Instant?,
        total: Long?,
        confirmed: Long,
        waitingQuantity: Int = 1,
        limitPerUser: Int? = null,
    ): ServiceFixture {
        val waitingQueueRepository = mock<WaitingQueueRepository>()
        whenever(waitingQueueRepository.admittedQuantityOf(any(), any())).thenReturn(null)
        // enter() 마지막의 resolveStatus가 읽는 원자 스냅샷 - 진입 가드와 동일한 상태를 반영한다.
        whenever(waitingQueueRepository.statusSnapshotOf(eq(dropId), eq(userId), any(), any()))
            .thenReturn(
                QueueStatusSnapshot(
                    admittedQuantity = null,
                    rank = 0,
                    totalWaiting = 1,
                    quantity = waitingQuantity,
                    remaining = remaining,
                    closeAt = closeAt,
                    outstanding = 0,
                    confirmed = confirmed,
                    total = total,
                    decision = null,
                    reserved = 0,
                ),
            )

        val stockRepository = mock<StockRepository>()
        whenever(stockRepository.snapshotOf(dropId))
            .thenReturn(DropStockSnapshot(remaining, closeAt, limitPerUser))

        val confirmedSalesRepository = mock<ConfirmedSalesRepository>()
        whenever(confirmedSalesRepository.totalOf(dropId)).thenReturn(total)
        whenever(confirmedSalesRepository.confirmedOf(dropId)).thenReturn(confirmed)

        val queueProperties = QueueProperties()
        val service = QueueService(waitingQueueRepository, stockRepository, confirmedSalesRepository, queueProperties)
        return ServiceFixture(service, waitingQueueRepository, confirmedSalesRepository, queueProperties.admission.ttlSeconds)
    }
}
