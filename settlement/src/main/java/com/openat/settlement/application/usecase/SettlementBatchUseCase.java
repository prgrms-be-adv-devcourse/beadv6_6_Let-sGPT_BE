package com.openat.settlement.application.usecase;

import com.openat.settlement.application.dto.CreateSettlementBatchCommand;
import com.openat.settlement.application.dto.FailSettlementBatchCommand;
import com.openat.settlement.application.dto.FinalizeSettlementBatchCommand;
import com.openat.settlement.domain.model.SettlementBatch;

/**
 * settlement_batchs 상태 관리 UseCase입니다.
 */
public interface SettlementBatchUseCase {

    SettlementBatch createAndStartBatch(CreateSettlementBatchCommand command);

    void finalizeSettlementRunBatch(FinalizeSettlementBatchCommand command);

    void failBatch(FailSettlementBatchCommand command);
}
