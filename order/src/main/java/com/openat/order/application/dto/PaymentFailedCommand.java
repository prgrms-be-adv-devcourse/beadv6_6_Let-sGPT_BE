package com.openat.order.application.dto;

import java.util.UUID;

public record PaymentFailedCommand(UUID orderId, String version, UUID paymentId, String reason) {
}
