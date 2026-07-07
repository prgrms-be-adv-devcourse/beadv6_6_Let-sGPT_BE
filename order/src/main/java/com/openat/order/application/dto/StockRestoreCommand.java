package com.openat.order.application.dto;

import java.util.UUID;

public record StockRestoreCommand(UUID orderId, UUID buyerId, int quantity) {
}
