package com.openat.order.infrastructure.kafka.event;

import java.time.Instant;
import java.util.UUID;

public record OrderCompletedEvent(
        UUID orderId,
        UUID sellerId,
        UUID productId,
        UUID memberId,
        long amount,
        Instant completedAt
) {
}
