package com.openat.settlement.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.settlement.application.dto.CreateSettlementBatchCommand;
import com.openat.settlement.application.dto.FailSettlementBatchCommand;
import com.openat.settlement.application.dto.FinalizeSettlementBatchCommand;
import com.openat.settlement.application.usecase.SettlementBatchUseCase;
import com.openat.settlement.domain.exception.SettlementErrorCode;
import com.openat.settlement.domain.model.SellerSettlement;
import com.openat.settlement.domain.model.SellerSettlementStatus;
import com.openat.settlement.domain.model.SettlementBatch;
import com.openat.settlement.domain.repository.SellerSettlementRepository;
import com.openat.settlement.domain.repository.SettlementBatchRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SettlementBatchService implements SettlementBatchUseCase {

    private final SettlementBatchRepository settlementBatchRepository;
    private final SellerSettlementRepository sellerSettlementRepository;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public SettlementBatch createAndStartBatch(CreateSettlementBatchCommand command) {
        SettlementBatch batch = SettlementBatch.create(
                command.settlementMonth(),
                command.batchType()
        );

        batch.start();
        return settlementBatchRepository.save(batch);
    }

    @Override
    @Transactional
    public void finalizeSettlementRunBatch(FinalizeSettlementBatchCommand command) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "fail";
        try {
            SettlementBatch batch = findBatch(command.batchId());

            long failedCount = sellerSettlementRepository.countByBatchIdAndStatus(
                    command.batchId(),
                    SellerSettlementStatus.FAILED
            );

            List<SellerSettlement> settlements = sellerSettlementRepository.findByBatchId(command.batchId());

            int totalOrderCount = settlements.stream()
                    .mapToInt(SellerSettlement::getTotalOrderCountOrZero)
                    .sum();

            long totalSettlementAmount = settlements.stream()
                    .filter(SellerSettlement::isCompleted)
                    .mapToLong(SellerSettlement::getFinalSettlementAmountOrZero)
                    .sum();

            int totalSellerCount = settlements.size();

            if (failedCount > 0) {
                batch.fail("failedSellerCount=" + failedCount + ", totalSellerCount=" + totalSellerCount);
                return;
            }

            batch.complete(
                    totalOrderCount,
                    totalSellerCount,
                    totalSettlementAmount
            );
            outcome = "success";
        } finally {
            sample.stop(meterRegistry.timer("settlement.batch", "outcome", outcome));
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failBatch(FailSettlementBatchCommand command) {
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            SettlementBatch batch = findBatch(command.batchId());

            batch.fail(command.reason());
        } finally {
            sample.stop(meterRegistry.timer("settlement.batch", "outcome", "fail"));
        }
    }

    private SettlementBatch findBatch(UUID batchId) {
        return settlementBatchRepository.findById(batchId)
                .orElseThrow(() -> new BusinessException(
                        SettlementErrorCode.BATCH_SETTLEMENT_NOT_FOUND,
                        "정산 배치를 찾을 수 없습니다. batchId=" + batchId
                ));
    }
}
