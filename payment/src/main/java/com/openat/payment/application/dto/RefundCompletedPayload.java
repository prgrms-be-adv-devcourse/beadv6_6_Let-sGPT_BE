package com.openat.payment.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

// refund.completed.events 발행 페이로드 — orderId는 paymentId로 Payment를 조인해서 채움(A6).
public record RefundCompletedPayload(UUID refundId, UUID paymentId, UUID orderId, Long amount,
        LocalDateTime refundedAt) {
}
