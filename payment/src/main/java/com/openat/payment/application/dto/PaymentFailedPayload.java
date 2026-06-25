package com.openat.payment.application.dto;

import java.util.UUID;

// payment.failed.events 발행 페이로드 — reason은 PG_REJECTED/EXPIRED 등으로 구분(하자드#23).
public record PaymentFailedPayload(UUID paymentId, UUID orderId, String reason) {
}
