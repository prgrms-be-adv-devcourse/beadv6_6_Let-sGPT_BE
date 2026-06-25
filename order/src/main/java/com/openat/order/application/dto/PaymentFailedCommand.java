package com.openat.order.application.dto;

import java.time.Instant;
import java.util.UUID;

public record PaymentFailedCommand(UUID orderId, String reason, Instant occurredAt, String eventId) {
}
