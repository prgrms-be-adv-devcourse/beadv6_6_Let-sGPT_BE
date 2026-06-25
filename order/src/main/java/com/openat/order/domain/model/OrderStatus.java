package com.openat.order.domain.model;

public enum OrderStatus {
    PAYMENT_PENDING,
    COMPLETED,
    FAILED,
    CANCELLED,
    CANCEL_REQUESTED,
    REFUND_PENDING,
    REFUNDED,
    REFUND_FAILED
}
