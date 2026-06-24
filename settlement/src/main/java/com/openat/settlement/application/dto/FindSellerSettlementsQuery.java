package com.openat.settlement.application.dto;

import com.openat.settlement.domain.model.SellerSettlementStatus;

import java.util.UUID;

public record FindSellerSettlementsQuery(
        String settlementMonth,
        UUID sellerId,
        SellerSettlementStatus status
) {
}
