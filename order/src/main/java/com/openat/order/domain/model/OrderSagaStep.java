package com.openat.order.domain.model;

public enum OrderSagaStep {
    ORDER_CREATED,
    STOCK_DECREASED,
    COMPLETED,
    COMPENSATING,
    COMPENSATION_COMPLETED
}
