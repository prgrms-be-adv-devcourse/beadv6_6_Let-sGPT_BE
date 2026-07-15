package com.openat.queue.domain.model

/**
 * order의 `StockAdjustment` Kafka 이벤트가 실어보내는 확정 재고 조정 사유.
 *
 * - [COMPLETED]: 결제가 확정돼 재고가 되돌릴 수 없이 팔렸다 - 큐의 확정 수량에 `+count`.
 * - [REFUNDED]: 확정됐던 주문이 환불됐다 - 큐의 확정 수량에서 `-count`(product 쪽에서
 *   remaining도 함께 복구된다 - 큐는 remaining을 읽기 전용으로만 참조하므로 별도 처리 불필요).
 */
enum class StockAdjustmentReason {
    COMPLETED,
    REFUNDED,
}
