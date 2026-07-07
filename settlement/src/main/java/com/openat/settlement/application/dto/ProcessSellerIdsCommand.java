package com.openat.settlement.application.dto;

import java.util.List;
import java.util.UUID;

/**
 * partition 하나에 포함된 sellerId 목록 처리 요청 DTO입니다.
 */
public record ProcessSellerIdsCommand(
        UUID batchId,
        String settlementMonth,
        List<UUID> sellerIds
) {
}
