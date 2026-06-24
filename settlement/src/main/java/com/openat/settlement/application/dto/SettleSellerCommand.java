package com.openat.settlement.application.dto;

import java.util.UUID;

/**
 * 판매자 1명에 대한 월 정산 실행 요청 DTO입니다.
 */
public record SettleSellerCommand(
        UUID batchId,
        UUID sellerId,
        String settlementMonth
) {
}
