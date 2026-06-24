package com.openat.settlement.application.usecase;

import com.openat.settlement.application.dto.RetryFailedSellerSettlementsCommand;
import com.openat.settlement.application.dto.RetryFailedSellerSettlementsResult;

public interface FailedSellerSettlementRetryUseCase {

    RetryFailedSellerSettlementsResult retryFailedSellerSettlements(
            RetryFailedSellerSettlementsCommand command
    );
}
