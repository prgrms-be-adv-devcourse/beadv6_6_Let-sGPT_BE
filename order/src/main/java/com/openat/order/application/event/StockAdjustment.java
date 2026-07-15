package com.openat.order.application.event;

import java.util.UUID;

public record StockAdjustment(
        UUID eventId,
        UUID dropId,
        int count,
        StockAdjustmentReason reason
) {
}
