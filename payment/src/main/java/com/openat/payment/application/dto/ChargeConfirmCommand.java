package com.openat.payment.application.dto;

import java.util.UUID;

// 충전 PG confirm 단계 입력(E1) — paymentKey는 브라우저가 토스 SDK로 직접 발급받아 전달받은 값.
public record ChargeConfirmCommand(UUID chargeId, UUID memberId, Long amount, String paymentKey, String idempotencyKey) {
}
