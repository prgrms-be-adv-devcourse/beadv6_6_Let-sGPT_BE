package com.openat.settlement.domain.repository;

import com.openat.settlement.domain.model.SellerSettlement;
import com.openat.settlement.domain.model.SellerSettlementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 판매자 정산 결과 저장소 계약입니다.
 */
public interface SellerSettlementRepository {

    Optional<SellerSettlement> findBySellerIdAndSettlementMonth(
            UUID sellerId,
            String settlementMonth
    );

    long countByBatchIdAndStatus(
            UUID batchId,
            SellerSettlementStatus status
    );

    List<SellerSettlement> findByBatchId(UUID batchId);

    List<SellerSettlement> findBySettlementMonthAndStatus(
            String settlementMonth,
            SellerSettlementStatus status
    );

    Page<SellerSettlement> findAllByConditions(
            String settlementMonth,
            UUID sellerId,
            SellerSettlementStatus status,
            Pageable pageable
    );

    SellerSettlement save(SellerSettlement sellerSettlement);
}
