package com.openat.settlement.application.service;

import com.openat.common.error.ErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.settlement.application.dto.ProcessSellerIdsCommand;
import com.openat.settlement.application.dto.RecordSellerSettlementFailureCommand;
import com.openat.settlement.application.dto.SettleSellerCommand;
import com.openat.settlement.application.usecase.SellerSettlementFailureUseCase;
import com.openat.settlement.application.usecase.SellerSettlementUseCase;
import com.openat.settlement.application.usecase.SellerSettlementWorkerUseCase;
import com.openat.settlement.domain.exception.SettlementErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * SellerSettlementWorkerUseCase 구현체입니다.
 *
 * partition 하나에 포함된 sellerId 목록을 순회합니다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerSettlementWorkerService implements SellerSettlementWorkerUseCase {

    private final SellerSettlementUseCase sellerSettlementUseCase;
    private final SellerSettlementFailureUseCase failureUseCase;

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void processSellerIds(ProcessSellerIdsCommand command) {
        for (UUID sellerId : command.sellerIds()) {
            try {
                sellerSettlementUseCase.settleSeller(new SettleSellerCommand(
                        command.batchId(),
                        sellerId,
                        command.settlementMonth()
                ));
            } catch (Exception e) {
                ErrorCode errorCode = resolveErrorCode(e);
                String reason = errorCode.getCode() + " - " + e.getClass().getSimpleName() + ": " + e.getMessage();
                log.warn(
                        "Seller settlement failed. errorCode={}, batchId={}, settlementMonth={}, sellerId={}",
                        errorCode.getCode(),
                        command.batchId(),
                        command.settlementMonth(),
                        sellerId,
                        e
                );
                failureUseCase.recordFailure(new RecordSellerSettlementFailureCommand(
                        command.batchId(),
                        sellerId,
                        command.settlementMonth(),
                        reason
                ));
            }
        }
    }

    private ErrorCode resolveErrorCode(Exception e) {
        if (e instanceof BusinessException businessException) {
            return businessException.getErrorCode();
        }

        return SettlementErrorCode.SELLER_SETTLEMENT_FAILED;
    }
}
