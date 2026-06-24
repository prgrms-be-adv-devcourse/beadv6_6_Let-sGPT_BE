package com.openat.settlement.application.dto;

import com.openat.settlement.domain.model.SettlementBatchStatus;

public record FindSettlementBatchResultsQuery(
        String settlementMonth,
        SettlementBatchStatus status
) {
}
