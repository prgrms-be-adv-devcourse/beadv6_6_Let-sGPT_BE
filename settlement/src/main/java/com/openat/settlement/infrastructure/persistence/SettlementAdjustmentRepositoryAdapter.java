package com.openat.settlement.infrastructure.persistence;

import com.openat.settlement.domain.model.AdjustmentStatus;
import com.openat.settlement.domain.model.SettlementAdjustment;
import com.openat.settlement.domain.repository.SettlementAdjustmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * domain.repository.SettlementAdjustmentRepository 계약을 JPA로 구현하는 Adapter입니다.
 */
@Repository
@RequiredArgsConstructor
public class SettlementAdjustmentRepositoryAdapter implements SettlementAdjustmentRepository {

    private final SettlementAdjustmentJpaRepository jpaRepository;

    @Override
    public List<SettlementAdjustment> findReadyAdjustments(
            UUID sellerId,
            String settlementMonth
    ) {
        return jpaRepository.findBySellerIdAndSettlementMonthAndStatus(
                sellerId,
                settlementMonth,
                AdjustmentStatus.READY
        );
    }

    @Override
    public long sumReadyAdjustmentAmount(
            UUID sellerId,
            String settlementMonth
    ) {
        return jpaRepository.sumAdjustmentAmountBySellerIdAndSettlementMonthAndStatus(
                sellerId,
                settlementMonth,
                AdjustmentStatus.READY
        );
    }

    @Override
    public int applyReadyAdjustments(
            UUID sellerId,
            String settlementMonth
    ) {
        return jpaRepository.applyBySellerIdAndSettlementMonthAndStatus(
                sellerId,
                settlementMonth,
                AdjustmentStatus.READY,
                AdjustmentStatus.APPLIED
        );
    }

    @Override
    public boolean existsByRefundId(UUID refundId) {
        return jpaRepository.existsByRefundId(refundId);
    }

    @Override
    public SettlementAdjustment save(SettlementAdjustment adjustment) {
        return jpaRepository.save(adjustment);
    }
}
