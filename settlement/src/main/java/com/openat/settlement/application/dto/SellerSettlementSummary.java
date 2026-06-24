package com.openat.settlement.application.dto;

import com.openat.settlement.domain.model.SellerSettlement;
import com.openat.settlement.domain.model.SellerSettlementStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record SellerSettlementSummary(
        UUID id,
        UUID batchId,
        String settlementMonth,
        UUID sellerId,
        int totalOrderCount,
        long totalPaidAmount,
        long totalFeeAmount,
        long totalRefundAmount,
        long totalAdjustmentAmount,
        long finalSettlementAmount,
        SellerSettlementStatus status,
        LocalDateTime completedAt,
        String failReason,
        LocalDateTime failedAt
) {

    public static SellerSettlementSummary from(SellerSettlement settlement) {
        return new SellerSettlementSummary(
                settlement.getId(),
                settlement.getBatchId(),
                settlement.getSettlementMonth(),
                settlement.getSellerId(),
                settlement.getTotalOrderCountOrZero(),
                settlement.getTotalPaidAmount(),
                settlement.getTotalFeeAmount(),
                settlement.getTotalRefundAmount(),
                settlement.getTotalAdjustmentAmount(),
                settlement.getFinalSettlementAmountOrZero(),
                settlement.getStatus(),
                settlement.getCompletedAt(),
                settlement.getFailReason(),
                settlement.getFailedAt()
        );
    }
}
