package com.openat.settlement.application.dto;

import java.util.UUID;

/**
 * 월 정산 배치 최종 집계 요청 DTO입니다.
 */
public record FinalizeSettlementBatchCommand(
        UUID batchId
) {
}
