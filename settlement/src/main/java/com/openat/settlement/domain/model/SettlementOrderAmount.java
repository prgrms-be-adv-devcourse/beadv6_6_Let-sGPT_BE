package com.openat.settlement.domain.model;

public record SettlementOrderAmount(
        long paidAmount,
        long feeAmount,
        long refundAmount
) {
}
