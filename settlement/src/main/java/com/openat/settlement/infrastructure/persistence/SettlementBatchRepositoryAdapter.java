package com.openat.settlement.infrastructure.persistence;

import com.openat.settlement.domain.model.SettlementBatch;
import com.openat.settlement.domain.model.SettlementBatchStatus;
import com.openat.settlement.domain.repository.SettlementBatchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * domain.repository.SettlementBatchRepository 계약을 JPA로 구현하는 Adapter입니다.
 */
@Repository
@RequiredArgsConstructor
public class SettlementBatchRepositoryAdapter implements SettlementBatchRepository {

    private final SettlementBatchJpaRepository jpaRepository;

    @Override
    public Optional<SettlementBatch> findById(UUID batchId) {
        return jpaRepository.findById(batchId);
    }

    @Override
    public Page<SettlementBatch> findAllByConditions(
            String settlementMonth,
            SettlementBatchStatus status,
            Pageable pageable
    ) {
        return jpaRepository.findAllByConditions(
                settlementMonth,
                status,
                pageable
        );
    }

    @Override
    public SettlementBatch save(SettlementBatch batch) {
        return jpaRepository.save(batch);
    }
}
