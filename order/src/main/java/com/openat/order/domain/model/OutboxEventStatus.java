package com.openat.order.domain.model;

public enum OutboxEventStatus {
    PENDING,
    PUBLISHED,
    FAILED
}
