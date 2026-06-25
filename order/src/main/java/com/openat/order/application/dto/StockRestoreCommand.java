package com.openat.order.application.dto;

import java.util.UUID;

public record StockRestoreCommand(UUID orderId, int quantity, String idempotencyKey) {
}

