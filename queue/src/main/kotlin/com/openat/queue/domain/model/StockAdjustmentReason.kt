package com.openat.queue.domain.model

/**
 * order의 `StockAdjustment` Kafka 이벤트가 실어보내는 재고 조정 사유. 두 켤레로 나뉜다:
 *
 * **확정(확정 판매 - 대기열 존속 판정용)**: 총재고 - 확정 = 0이면 매진 → 대기 중인 유저에게
 * 매진을 알리고 추가 대기를 받지 않는다([com.openat.queue.domain.repository.ConfirmedSalesRepository]).
 * - [COMPLETED]: 결제가 확정돼 재고가 되돌릴 수 없이 팔렸다 - 큐의 확정 수량에 `+count`.
 * - [REFUNDED]: 확정됐던 주문이 환불됐다 - 큐의 확정 수량에서 `-count`(product 쪽에서
 *   remaining도 함께 복구된다 - 큐는 remaining을 읽기 전용으로만 참조하므로 별도 처리 불필요).
 *
 * **선점(구매자 입장 시 분기 관리용 - 기다릴지/조정 구매할지/포기할지)**: 선점된 재고 중
 * 아직 확정되지 않은 만큼은 재고 복구 가능성이 있는 재고다
 * ([com.openat.queue.domain.repository.ReservedStockRepository]).
 * - [CREATED]: 주문이 생성돼(결제 대기) 재고를 선점했다 - 선점 수량에 `+count`.
 * - [CANCELLED]: 선점됐던 주문이 취소/만료됐다 - 선점 수량에서 `-count`.
 *
 * COMPLETED/REFUNDED/CREATED/CANCELLED 전부 교환 가능(덧셈/뺄셈)한 연산이라 도착 순서와
 * 무관하게 최종 합계가 항상 같다 - 이벤트 순서를 보장할 필요가 없다(각 켤레는 서로 다른
 * Redis 카운터를 가감하므로 두 켤레 사이의 순서도 무관).
 */
enum class StockAdjustmentReason {
    COMPLETED,
    REFUNDED,
    CREATED,
    CANCELLED,
}
