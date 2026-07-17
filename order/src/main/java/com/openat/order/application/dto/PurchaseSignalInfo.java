package com.openat.order.application.dto;

import com.openat.order.domain.model.PurchaseSignal;
import java.time.Instant;
import java.util.UUID;

public record PurchaseSignalInfo(
        UUID productId,
        long orderCount,
        long totalQuantity,
        Instant lastOrderedAt) {

    public static PurchaseSignalInfo from(PurchaseSignal signal) {
        return new PurchaseSignalInfo(
                signal.productId(),
                signal.orderCount(),
                signal.totalQuantity(),
                signal.lastOrderedAt());
    }
}
