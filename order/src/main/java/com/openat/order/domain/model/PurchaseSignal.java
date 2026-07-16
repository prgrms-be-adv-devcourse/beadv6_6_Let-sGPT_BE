package com.openat.order.domain.model;

import java.time.Instant;
import java.util.UUID;

public record PurchaseSignal(
        UUID productId,
        long orderCount,
        long totalQuantity,
        Instant lastOrderedAt) {
}
