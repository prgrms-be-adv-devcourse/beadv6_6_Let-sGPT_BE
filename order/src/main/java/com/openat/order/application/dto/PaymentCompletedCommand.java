package com.openat.order.application.dto;

import java.util.UUID;

public record PaymentCompletedCommand(UUID orderId, UUID paymentId, long amount) {
}
