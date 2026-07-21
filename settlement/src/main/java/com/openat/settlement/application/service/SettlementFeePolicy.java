package com.openat.settlement.application.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SettlementFeePolicy {

  private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);

  private final int feeRatePercent;

  public SettlementFeePolicy(@Value("${settlement.fee-rate-percent:3}") int feeRatePercent) {
    if (feeRatePercent < 0 || feeRatePercent > 100) {
      throw new IllegalArgumentException("feeRatePercent must be between 0 and 100");
    }
    this.feeRatePercent = feeRatePercent;
  }

  public long calculate(long paidAmount) {
    if (paidAmount < 0) {
      throw new IllegalArgumentException("paidAmount must not be negative");
    }

    return BigDecimal.valueOf(paidAmount)
        .multiply(BigDecimal.valueOf(feeRatePercent))
        .divide(ONE_HUNDRED, 0, RoundingMode.DOWN)
        .longValueExact();
  }

  public int getFeeRatePercent() {
    return feeRatePercent;
  }
}
