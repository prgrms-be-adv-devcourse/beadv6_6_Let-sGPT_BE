package com.openat.order.application.dto;

import java.util.UUID;

public record StockRollbackTarget(UUID orderId, UUID dropId, UUID memberId, int quantity) {}
