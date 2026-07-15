package com.openat.payment.application.dto;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.exception.PaymentErrorCode;
import java.util.UUID;

public record RefundCommand(
    UUID paymentId, UUID memberId, Long amount, String reason, String idempotencyKey) {

  // §4.3 결함 — 환불한도 우회 경로 차단이라 특히 중요.
  public RefundCommand {
    if (amount == null || amount <= 0) {
      throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
    }
  }
}
