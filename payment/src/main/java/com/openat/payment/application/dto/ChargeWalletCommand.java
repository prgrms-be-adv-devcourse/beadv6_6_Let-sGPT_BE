package com.openat.payment.application.dto;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.exception.PaymentErrorCode;
import java.util.UUID;

public record ChargeWalletCommand(UUID memberId, Long amount, String idempotencyKey) {

  // §4.3 결함 — 레이어 무관 방어(내부 호출·컨슈머 경로도 커버).
  public ChargeWalletCommand {
    if (amount == null || amount <= 0) {
      throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
    }
  }
}
