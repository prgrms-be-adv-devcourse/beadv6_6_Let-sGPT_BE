package com.openat.settlement.application.dto;

import java.util.UUID;

/**
 * 정산 배치 실패 처리 요청 DTO입니다.
 */
public record FailSettlementBatchCommand(
        UUID batchId,
        String reason
) {
}
