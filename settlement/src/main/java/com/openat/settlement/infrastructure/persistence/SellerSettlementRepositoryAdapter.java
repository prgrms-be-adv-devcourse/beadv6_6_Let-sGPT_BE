package com.openat.settlement.infrastructure.persistence;

import com.openat.settlement.domain.model.SellerSettlement;
import com.openat.settlement.domain.model.SellerSettlementStatus;
import com.openat.settlement.domain.repository.SellerSettlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * domain.repository.SellerSettlementRepository 계약을 JPA로 구현하는 Adapter입니다.
 */
@Repository
@RequiredArgsConstructor
public class SellerSettlementRepositoryAdapter implements SellerSettlementRepository {

    private final SellerSettlementJpaRepository jpaRepository;

    @Override
    public Optional<SellerSettlement> findBySellerIdAndSettlementMonth(
            UUID sellerId,
            String settlementMonth
    ) {
        return jpaRepository.findBySellerIdAndSettlementMonth(sellerId, settlementMonth);
    }

    @Override
    public long countByBatchIdAndStatus(
            UUID batchId,
            SellerSettlementStatus status
    ) {
        return jpaRepository.countByBatchIdAndStatus(batchId, status);
    }

    @Override
    public List<SellerSettlement> findByBatchId(UUID batchId) {
        return jpaRepository.findByBatchId(batchId);
    }

    @Override
    public List<SellerSettlement> findBySettlementMonthAndStatus(
            String settlementMonth,
            SellerSettlementStatus status
    ) {
        return jpaRepository.findBySettlementMonthAndStatus(settlementMonth, status);
    }

    @Override
    public Page<SellerSettlement> findAllByConditions(
            String settlementMonth,
            UUID sellerId,
            SellerSettlementStatus status,
            Pageable pageable
    ) {
        return jpaRepository.findAllByConditions(
                settlementMonth,
                sellerId,
                status,
                pageable
        );
    }

    @Override
    public SellerSettlement save(SellerSettlement sellerSettlement) {
        return jpaRepository.save(sellerSettlement);
    }
}
