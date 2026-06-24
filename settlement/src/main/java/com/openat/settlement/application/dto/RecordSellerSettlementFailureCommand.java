package com.openat.settlement.application.dto;

import java.util.UUID;

/**
 * 판매자 1명에 대한 정산 실패 기록 요청 DTO입니다.
 */
public record RecordSellerSettlementFailureCommand(
        UUID batchId,
        UUID sellerId,
        String settlementMonth,
        String reason
) {
}
