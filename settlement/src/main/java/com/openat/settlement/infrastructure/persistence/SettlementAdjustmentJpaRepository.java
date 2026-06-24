package com.openat.settlement.infrastructure.persistence;

import com.openat.settlement.domain.model.AdjustmentStatus;
import com.openat.settlement.domain.model.SettlementAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

/**
 * Spring Data JPA 전용 정산 보정 Repository입니다.
 */
public interface SettlementAdjustmentJpaRepository extends JpaRepository<SettlementAdjustment, UUID> {

    List<SettlementAdjustment> findBySellerIdAndSettlementMonthAndStatus(
            UUID sellerId,
            String settlementMonth,
            AdjustmentStatus status
    );

    boolean existsByRefundId(UUID refundId);

    @Query("""
            select coalesce(sum(a.adjustmentAmount), 0)
            from SettlementAdjustment a
            where a.sellerId = :sellerId
              and a.settlementMonth = :settlementMonth
              and a.status = :status
            """)
    long sumAdjustmentAmountBySellerIdAndSettlementMonthAndStatus(
            UUID sellerId,
            String settlementMonth,
            AdjustmentStatus status
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update SettlementAdjustment a
            set a.status = :appliedStatus
            where a.sellerId = :sellerId
              and a.settlementMonth = :settlementMonth
              and a.status = :readyStatus
            """)
    int applyBySellerIdAndSettlementMonthAndStatus(
            UUID sellerId,
            String settlementMonth,
            AdjustmentStatus readyStatus,
            AdjustmentStatus appliedStatus
    );
}
