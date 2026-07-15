package com.openat.payment.application.dto;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.exception.PaymentErrorCode;
import java.util.UUID;

public record PayWithWalletCommand(
    UUID orderId, UUID memberId, Long amount, String idempotencyKey) {

  // §4.3 결함 — tryDeduct의 balance>=:amount 조건절이 음수 amount에 항상 참이 되어 잔액이 오히려 증가할 수 있었음.
  public PayWithWalletCommand {
    if (amount == null || amount <= 0) {
      throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
    }
  }
}
