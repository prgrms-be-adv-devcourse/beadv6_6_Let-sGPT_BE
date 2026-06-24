package com.openat.settlement.infrastructure.persistence;

import com.openat.settlement.domain.model.SettlementBatch;
import com.openat.settlement.domain.model.SettlementBatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

/**
 * Spring Data JPA 전용 정산 배치 Repository입니다.
 */
public interface SettlementBatchJpaRepository extends JpaRepository<SettlementBatch, UUID> {

    @Query("""
            select b
            from SettlementBatch b
            where (:settlementMonth is null or b.settlementMonth = :settlementMonth)
              and (:status is null or b.status = :status)
            order by b.createdAt desc
            """)
    Page<SettlementBatch> findAllByConditions(
            String settlementMonth,
            SettlementBatchStatus status,
            Pageable pageable
    );
}
