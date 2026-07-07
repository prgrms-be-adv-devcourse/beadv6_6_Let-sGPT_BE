package com.openat.order.application.dto;

import java.util.UUID;

public record RefundFailedCommand(UUID orderId, UUID paymentId, UUID refundId, String reason) {
}
