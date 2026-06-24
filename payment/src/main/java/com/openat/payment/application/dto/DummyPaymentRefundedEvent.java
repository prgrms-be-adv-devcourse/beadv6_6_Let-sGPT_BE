package com.openat.payment.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

// 정산팀 컨슈머 테스트용 더미 발행 전용 — 기존 RefundSettlementSourcePayload와 무관(스키마가 다름, envelope 포함).
// 실제 운영 발행 경로에는 쓰이지 않는다(personal_workplan/research.md §18.2).
public record DummyPaymentRefundedEvent(String eventId, String eventType, LocalDateTime occurredAt,
        UUID refundId, UUID paymentId, UUID orderId, UUID sellerId, UUID buyerId, long refundAmount,
        String refundReason, LocalDateTime refundedAt) {
}
