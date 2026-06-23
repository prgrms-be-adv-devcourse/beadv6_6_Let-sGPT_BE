package com.openat.payment.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

// refund.settlement-source.events 발행 페이로드 — sellerId는 결제 쪽 사후채움이 아직 안 끝났으면 null일 수 있음
// (B6 — 정산 쪽이 보류/재시도하는 전제, 결제 쪽에서 막지 않고 그대로 발행).
public record RefundSettlementSourcePayload(UUID refundId, UUID paymentId, UUID orderId, UUID sellerId,
        UUID buyerId, Long refundAmount, String refundReason, String refundStatus, LocalDateTime refundedAt) {
}
