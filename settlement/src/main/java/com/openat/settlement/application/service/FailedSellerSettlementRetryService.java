package com.openat.settlement.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.settlement.application.dto.CreateSettlementBatchCommand;
import com.openat.settlement.application.dto.FinalizeSettlementBatchCommand;
import com.openat.settlement.application.dto.ProcessSellerIdsCommand;
import com.openat.settlement.application.dto.RetryFailedSellerSettlementsCommand;
import com.openat.settlement.application.dto.RetryFailedSellerSettlementsResult;
import com.openat.settlement.application.usecase.FailedSellerSettlementRetryUseCase;
import com.openat.settlement.application.usecase.SellerSettlementWorkerUseCase;
import com.openat.settlement.application.usecase.SettlementBatchUseCase;
import com.openat.settlement.domain.exception.SettlementErrorCode;
import com.openat.settlement.domain.model.SellerSettlement;
import com.openat.settlement.domain.model.SellerSettlementStatus;
import com.openat.settlement.domain.model.SettlementBatch;
import com.openat.settlement.domain.model.SettlementBatchStatus;
import com.openat.settlement.domain.model.SettlementBatchType;
import com.openat.settlement.domain.repository.SellerSettlementRepository;
import com.openat.settlement.domain.repository.SettlementBatchRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FailedSellerSettlementRetryService implements FailedSellerSettlementRetryUseCase {

    private final SellerSettlementRepository sellerSettlementRepository;
    private final SettlementBatchRepository settlementBatchRepository;
    private final SettlementBatchUseCase settlementBatchUseCase;
    private final SellerSettlementWorkerUseCase sellerSettlementWorkerUseCase;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RetryFailedSellerSettlementsResult retryFailedSellerSettlements(
            RetryFailedSellerSettlementsCommand command
    ) {
        List<UUID> failedSellerIds = sellerSettlementRepository
                .findBySettlementMonthAndStatus(
                        command.settlementMonth(),
                        SellerSettlementStatus.FAILED
                )
                .stream()
                .map(SellerSettlement::getSellerId)
                .distinct()
                .toList();

        if (failedSellerIds.isEmpty()) {
            return new RetryFailedSellerSettlementsResult(
                    null,
                    command.settlementMonth(),
                    0,
                    SettlementBatchStatus.COMPLETED,
                    null
            );
        }

        meterRegistry.counter("settlement.retry.count").increment(failedSellerIds.size());

        SettlementBatch batch = settlementBatchUseCase.createAndStartBatch(
                new CreateSettlementBatchCommand(
                        command.settlementMonth(),
                        SettlementBatchType.SETTLEMENT_RETRY
                )
        );

        sellerSettlementWorkerUseCase.processSellerIds(
                new ProcessSellerIdsCommand(
                        batch.getId(),
                        command.settlementMonth(),
                        failedSellerIds
                )
        );

        settlementBatchUseCase.finalizeSettlementRunBatch(
                new FinalizeSettlementBatchCommand(batch.getId())
        );

        SettlementBatch finalizedBatch = settlementBatchRepository.findById(batch.getId())
                .orElseThrow(() -> new BusinessException(
                        SettlementErrorCode.BATCH_SETTLEMENT_NOT_FOUND,
                        "재처리 정산 배치를 찾을 수 없습니다. batchId=" + batch.getId()
                ));

        return new RetryFailedSellerSettlementsResult(
                finalizedBatch.getId(),
                finalizedBatch.getSettlementMonth(),
                failedSellerIds.size(),
                finalizedBatch.getStatus(),
                finalizedBatch.getFailReason()
        );
    }
}
