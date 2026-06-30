package com.openat.order.application.dto;

import java.util.UUID;

public record OrderSnapshotInfo(
        UUID productId,
        UUID sellerId,
        String productName,
        long unitPrice) {
}
