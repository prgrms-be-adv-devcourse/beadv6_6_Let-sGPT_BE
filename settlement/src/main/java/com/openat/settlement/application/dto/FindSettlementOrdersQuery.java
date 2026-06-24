package com.openat.settlement.application.dto;

import com.openat.settlement.domain.model.SettlementOrderStatus;

import java.util.UUID;

public record FindSettlementOrdersQuery(
        String settlementMonth,
        SettlementOrderStatus status,
        UUID sellerId,
        UUID orderId
) {
}
