package com.openat.payment.application.dto;

import java.util.UUID;

// PG confirm 단계 입력(A16) — paymentKey는 브라우저가 토스 SDK로 직접 발급받아 successUrl로 전달받은 값.
public record PgConfirmCommand(UUID orderId, UUID memberId, Long amount, String paymentKey, String idempotencyKey) {
}
