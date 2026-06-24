package com.openat.settlement.application.dto;

import com.openat.settlement.domain.model.SettlementBatch;
import com.openat.settlement.domain.model.SettlementBatchStatus;
import com.openat.settlement.domain.model.SettlementBatchType;

import java.time.LocalDateTime;
import java.util.UUID;

public record SettlementBatchResultSummary(
        UUID batchId,
        String settlementMonth,
        SettlementBatchType batchType,
        SettlementBatchStatus status,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        int totalOrderCount,
        int totalSellerCount,
        long totalSettlementAmount,
        String failReason,
        LocalDateTime createdAt
) {

    public static SettlementBatchResultSummary from(SettlementBatch batch) {
        return new SettlementBatchResultSummary(
                batch.getId(),
                batch.getSettlementMonth(),
                batch.getBatchType(),
                batch.getStatus(),
                batch.getStartedAt(),
                batch.getEndedAt(),
                batch.getTotalOrderCount(),
                batch.getTotalSellerCount(),
                batch.getTotalSettlementAmount(),
                batch.getFailReason(),
                batch.getCreatedAt()
        );
    }
}
