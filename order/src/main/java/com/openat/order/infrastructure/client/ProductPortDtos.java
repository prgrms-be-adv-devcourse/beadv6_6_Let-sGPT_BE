package com.openat.order.infrastructure.client;

import java.util.UUID;

public final class ProductPortDtos {

    private ProductPortDtos() {}

    public record OrderSnapshotResponse(
            UUID productId,
            UUID sellerId,
            long unitPrice) {
    }

    public record StockChangeRequest(UUID orderId, UUID buyerId, int quantity) {
    }

    public enum OperationType {
        FETCH_ORDER_SNAPSHOT,
        DECREASE_STOCK,
        RESTORE_STOCK
    }
}
