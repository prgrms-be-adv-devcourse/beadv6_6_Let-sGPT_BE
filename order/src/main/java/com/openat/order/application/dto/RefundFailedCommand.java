package com.openat.order.application.dto;

import java.util.UUID;

public record RefundFailedCommand(UUID orderId, String reason, String eventId) {
}
