package com.openat.recommendation.domain.model;

import java.time.Instant;
import java.util.UUID;

public record PurchaseSignal(
    UUID productId, long orderCount, long totalQuantity, Instant lastOrderedAt) {}
