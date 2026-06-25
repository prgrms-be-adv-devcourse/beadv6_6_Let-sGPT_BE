package com.openat.order.domain.model;

public enum OrderFailCode {
    SOLD_OUT,
    NOT_OPEN,
    LIMIT_EXCEEDED,
    PAYMENT_FAILED,
    PAYMENT_EXPIRED,
    PAYMENT_STATUS_CHECK_FAILED,
    PG_ERROR,
    STOCK_ROLLBACK_FAILED
}
