package com.openat.settlement.application.usecase;

import com.openat.settlement.application.dto.RunMonthlySettlementResult;

public interface MonthlySettlementJobUseCase {

  RunMonthlySettlementResult run(String settlementMonth);
}
