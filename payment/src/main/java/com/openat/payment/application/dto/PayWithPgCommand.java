package com.openat.payment.application.dto;

import java.util.UUID;

public record PayWithPgCommand(UUID orderId, UUID memberId, Long amount, String idempotencyKey) {
}
