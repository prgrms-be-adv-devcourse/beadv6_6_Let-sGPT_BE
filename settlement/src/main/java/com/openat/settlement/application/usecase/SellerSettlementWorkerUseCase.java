package com.openat.settlement.application.usecase;

import com.openat.settlement.application.dto.ProcessSellerIdsCommand;

/**
 * partition에 들어온 sellerId 목록 처리 UseCase입니다.
 */
public interface SellerSettlementWorkerUseCase {

    void processSellerIds(ProcessSellerIdsCommand command);
}
