package com.openat.payment.application.dto;

import java.util.UUID;

public record RefundCommand(UUID paymentId, UUID memberId, Long amount, String reason, String idempotencyKey) {
}
