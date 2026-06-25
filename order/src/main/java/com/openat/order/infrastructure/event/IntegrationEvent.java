package com.openat.order.infrastructure.event;

import java.time.Instant;

public record IntegrationEvent<T>(
        String eventId,
        String eventType,
        String eventVersion,
        Instant occurredAt,
        String producer,
        String aggregateType,
        String aggregateId,
        String traceId,
        T payload) {
}
