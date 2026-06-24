package com.openat.settlement.domain.model;

public record SettlementOrderAggregate(
        long totalOrderCount,
        long totalPaidAmount,
        long totalFeeAmount,
        long totalRefundAmount
) {
}
