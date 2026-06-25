package com.openat.order.application.dto;

import java.util.UUID;

public record StockDecreaseCommand(UUID orderId, int quantity, String idempotencyKey) {
}

