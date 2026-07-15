package com.openat.settlement.infrastructure.client.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// payment 서비스의 정산 대사 일별 API(personal_workplan/plan/reconciliation.md) 응답 미러 — WS-3.
// payment 쪽 DailyPaymentSettlementResponse와 필드를 1:1로 맞춘다(계약은 reconciliation.md가 원본).
public record DailyPaymentSettlementResponse(
        String businessDate,
        List<PaymentItem> payments,
        List<RefundItem> refunds,
        Summary summary) {

    public record PaymentItem(
            UUID paymentId, UUID orderId, UUID sellerId, UUID buyerId, UUID productId,
            Long orderAmount, Long paidAmount, Long feeAmount, String paymentStatus,
            OffsetDateTime paidAt, String settlementMonth) {
    }

    public record RefundItem(
            UUID refundId, UUID paymentId, UUID orderId, UUID sellerId, UUID buyerId,
            Long refundAmount, String refundReason, String refundStatus,
            OffsetDateTime refundedAt, String settlementMonth) {
    }

    public record Summary(
            int paymentCount, long totalPaymentAmount, int refundCount, long totalRefundAmount,
            long expectedSettlementAmount) {
    }
}
