package com.openat.order.infrastructure.event;

import java.time.Instant;
import java.util.UUID;

public record OrderCompletedPayload(
        UUID orderId,
        UUID sellerId,
        UUID productId,
        UUID memberId,
        long saleAmount,
        int quantity,
        Instant completedAt) {
}
