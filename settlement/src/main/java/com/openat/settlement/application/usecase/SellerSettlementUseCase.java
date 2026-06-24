package com.openat.settlement.application.usecase;

import com.openat.settlement.application.dto.SettleSellerCommand;

/**
 * 판매자 1명에 대한 월 정산 계산 UseCase입니다.
 */
public interface SellerSettlementUseCase {

    void settleSeller(SettleSellerCommand command);
}
