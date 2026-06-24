package com.openat.payment.application.dto;

import java.util.UUID;

public record RefundFailedPayload(UUID refundId, UUID paymentId, UUID orderId, String reason) {
}
