package com.openat.payment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 정산 대사 일별 API(personal_workplan/plan/reconciliation.md, WS-2) — 정산팀이 요구한 응답 스키마 그대로.
// PG 대사 MATCHED 행만 포함한다(WS-0) — pg_recon_status가 MISMATCH/NOT_CHECKED인 행은 이 응답에 나타나지 않는다.
public record DailyPaymentSettlementResponse(
        @Schema(description = "조회 영업일", example = "2026-07-12") String businessDate,
        List<PaymentItem> payments,
        List<RefundItem> refunds,
        Summary summary) {

    public record PaymentItem(
            UUID paymentId,
            UUID orderId,
            @Schema(description = "sellerId(order_completed 사후채움 전이면 null)") UUID sellerId,
            UUID buyerId,
            @Schema(description = "productId(order_completed 사후채움 전이면 null)") UUID productId,
            Long orderAmount,
            Long paidAmount,
            @Schema(description = "수수료 — 결제 도메인엔 개념이 없어 항상 0, 정산측 책임") Long feeAmount,
            String paymentStatus,
            OffsetDateTime paidAt,
            @Schema(description = "yyyyMM, paidAt 기준 파생") String settlementMonth) {
    }

    public record RefundItem(
            UUID refundId,
            UUID paymentId,
            UUID orderId,
            UUID sellerId,
            UUID buyerId,
            Long refundAmount,
            String refundReason,
            String refundStatus,
            OffsetDateTime refundedAt,
            @Schema(description = "yyyyMM, refundedAt 기준 파생") String settlementMonth) {
    }

    public record Summary(
            int paymentCount,
            long totalPaymentAmount,
            int refundCount,
            long totalRefundAmount,
            @Schema(description = "totalPaymentAmount - totalRefundAmount") long expectedSettlementAmount) {
    }
}
