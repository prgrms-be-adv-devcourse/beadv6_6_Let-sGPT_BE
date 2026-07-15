package com.openat.order.domain.model;

public enum OrderStatus {
    PAYMENT_PENDING,
    COMPLETED,
    FAILED,
    CANCELLED,
    CANCEL_REQUESTED,
    // [파이널 예약] payment가 refund.requested(환불 접수) 이벤트를 발행할 때만 사용한다.
    // 현재는 이 상태로 전이하는 경로가 없어 도달 불가(설계문서 §5.1). 접수 이벤트 확정 시 전이 추가.
    REFUND_PENDING,
    REFUNDED,
    REFUND_FAILED
}
