package com.openat.settlement.domain.repository;

import com.openat.settlement.domain.model.SettlementAdjustment;

import java.util.List;
import java.util.UUID;

/**
 * 정산 보정 저장소 계약입니다.
 */
public interface SettlementAdjustmentRepository {

    List<SettlementAdjustment> findReadyAdjustments(
            UUID sellerId,
            String settlementMonth
    );

    long sumReadyAdjustmentAmount(
            UUID sellerId,
            String settlementMonth
    );

    int applyReadyAdjustments(
            UUID sellerId,
            String settlementMonth
    );

    boolean existsByRefundId(UUID refundId);

    SettlementAdjustment save(SettlementAdjustment adjustment);
}
