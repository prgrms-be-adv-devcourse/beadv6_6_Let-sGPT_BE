package com.openat.order.application.dto;

import java.time.Instant;
import java.util.UUID;

public record RefundCompletedCommand(UUID orderId, long amount, Instant occurredAt, String eventId) {
}
