package com.openat.settlement.infrastructure.persistence;

import com.openat.settlement.domain.model.SellerSettlement;
import com.openat.settlement.domain.model.SellerSettlementStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA 전용 판매자 정산 Repository입니다.
 */
public interface SellerSettlementJpaRepository extends JpaRepository<SellerSettlement, UUID> {

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

    @Query("""
            select s
            from SellerSettlement s
            where (:settlementMonth is null or s.settlementMonth = :settlementMonth)
              and (:sellerId is null or s.sellerId = :sellerId)
              and (:status is null or s.status = :status)
            order by s.settlementMonth desc, s.sellerId asc
            """)
    Page<SellerSettlement> findAllByConditions(
            String settlementMonth,
            UUID sellerId,
            SellerSettlementStatus status,
            Pageable pageable
    );
}
