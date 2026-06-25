package com.openat.order.application.dto;

import java.util.UUID;

public record ProductOrderSnapshot(
        UUID dropId,
        UUID productId,
        UUID sellerId,
        String productName,
        long unitPrice) {
}
