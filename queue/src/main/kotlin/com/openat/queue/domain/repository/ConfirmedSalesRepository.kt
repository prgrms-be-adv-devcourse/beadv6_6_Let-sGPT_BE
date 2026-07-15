package com.openat.queue.domain.repository

import com.openat.queue.domain.model.StockAdjustmentReason

/**
 * "낙관적 최대(optimisticMax) = 총재고 - 확정(결제완료) 수량" 및 "미확정 재고(=총재고 -
 * remaining - 확정)" 계산에 필요한 값을 다룬다.
 *
 * `remaining`(product 소유, [StockRepository])과 달리 이 값들은 **queue 자신이 소유하는
 * 파생 데이터**다: 총재고는 product의 값을 한 번 읽어와 캐싱한 것이고, 확정 수량은 order의
 * `StockAdjustment` Kafka 이벤트(결제 확정/환불)를 큐가 직접 구독해 가감한 것 - order/product에
 * 새 계산을 떠넘기지 않고, "이 값이 왜 필요한지" 아는 큐가 직접 조합한다.
 */
interface ConfirmedSalesRepository {

    /** 지금까지 결제 완료(확정)된 누적 수량(환불로 되돌아간 만큼은 이미 차감됨). 이벤트가 아직 안 왔으면 0. */
    fun confirmedOf(dropId: String): Long

    /**
     * `StockAdjustment` 이벤트를 소비해 확정 수량을 가감한다 - [StockAdjustmentReason.COMPLETED]면
     * `+count`, [StockAdjustmentReason.REFUNDED]면 `-count`. [eventId] 기준으로 멱등 처리한다 -
     * Kafka는 최소 1회(at-least-once) 전달이라 같은 이벤트가 재전달될 수 있는데, 그때마다
     * 중복 반영하면 확정 수량이 실제와 어긋난다(COMPLETED 중복이면 과대, REFUNDED 중복이면
     * 과소 계산 - 둘 다 위험하다). COMPLETED와 REFUNDED는 같은 주문이라도 서로 다른 사건이므로
     * 서로 다른 eventId를 갖는다는 전제(order/payment 팀과 합의됨).
     */
    fun applyStockAdjustment(dropId: String, eventId: String, count: Int, reason: StockAdjustmentReason)

    /**
     * 드롭의 총 수량(불변값). 캐시에 없으면 product에 1회 조회해 캐싱한다.
     * @return 조회 실패(드롭 없음/product 응답 불가)면 null - 호출부는 "낙관적 최대를 모른다"로 처리해야 한다.
     */
    fun totalOf(dropId: String): Long?
}
