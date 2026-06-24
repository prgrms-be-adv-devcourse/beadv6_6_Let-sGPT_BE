package com.openat.settlement.application.dto;

import com.openat.settlement.domain.model.SettlementBatchStatus;

import java.util.UUID;

public record RetryFailedSellerSettlementsResult(
        UUID batchId,
        String settlementMonth,
        int retriedSellerCount,
        SettlementBatchStatus status,
        String failReason
) {
}
