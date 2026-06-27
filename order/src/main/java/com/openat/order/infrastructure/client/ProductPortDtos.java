package com.openat.order.infrastructure.client;

import java.util.UUID;

public final class ProductPortDtos {

    private ProductPortDtos() {}

    public record OrderSnapshotResponse(
            UUID dropId,
            UUID productId,
            UUID sellerId,
            String productName,
            long unitPrice) {
    }

    public record StockChangeRequest(UUID orderId, int quantity, String idempotencyKey) {
    }

    public enum OperationType {
        FETCH_ORDER_SNAPSHOT,
        DECREASE_STOCK,
        RESTORE_STOCK
    }
}
