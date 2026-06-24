package com.openat.settlement.application.service;

import com.openat.settlement.application.dto.RecordSellerSettlementFailureCommand;
import com.openat.settlement.application.usecase.SellerSettlementFailureUseCase;
import com.openat.settlement.domain.model.SellerSettlement;
import com.openat.settlement.domain.repository.SellerSettlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SellerSettlementFailureUseCase 구현체입니다.
 *
 * 판매자 정산 실패 상태를 별도 트랜잭션으로 기록합니다.
 */
@Service
@RequiredArgsConstructor
public class SellerSettlementFailureRecorder implements SellerSettlementFailureUseCase {

    private final SellerSettlementRepository sellerSettlementRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(RecordSellerSettlementFailureCommand command) {
        SellerSettlement settlement = sellerSettlementRepository
                .findBySellerIdAndSettlementMonth(command.sellerId(), command.settlementMonth())
                .orElseGet(() -> SellerSettlement.create(
                        command.batchId(),
                        command.settlementMonth(),
                        command.sellerId(),
                        0,
                        0L,
                        0L,
                        0L,
                        0L
                ));

        settlement.fail(command.batchId(), command.reason());
        sellerSettlementRepository.save(settlement);
    }
}
