package com.openat.order.application.dto;

import java.util.UUID;

public record RefundCompletedCommand(UUID orderId, UUID paymentId, long amount, UUID refundId) {
}
