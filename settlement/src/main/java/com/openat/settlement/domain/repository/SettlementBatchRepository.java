package com.openat.settlement.domain.repository;

import com.openat.settlement.domain.model.SettlementBatch;
import com.openat.settlement.domain.model.SettlementBatchStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/**
 * 정산 배치 저장소 계약입니다.
 */
public interface SettlementBatchRepository {

    Optional<SettlementBatch> findById(UUID batchId);

    Page<SettlementBatch> findAllByConditions(
            String settlementMonth,
            SettlementBatchStatus status,
            Pageable pageable
    );

    SettlementBatch save(SettlementBatch batch);
}
