package com.openat.order.application.dto;

import java.util.UUID;

public record StockDecreaseCommand(UUID orderId, UUID buyerId, int quantity) {
}
