package com.openat.settlement.application.usecase;

import com.openat.settlement.application.dto.RecordSellerSettlementFailureCommand;

/**
 * 판매자 정산 실패 기록 UseCase입니다.
 */
public interface SellerSettlementFailureUseCase {

    void recordFailure(RecordSellerSettlementFailureCommand command);
}
