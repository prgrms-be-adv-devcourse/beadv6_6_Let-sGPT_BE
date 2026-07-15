package com.openat.payment.application.dto;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.exception.PaymentErrorCode;
import java.util.UUID;

// 충전 PG confirm 단계 입력(E1) — paymentKey는 브라우저가 토스 SDK로 직접 발급받아 전달받은 값.
public record ChargeConfirmCommand(
    UUID chargeId, UUID memberId, Long amount, String paymentKey, String idempotencyKey) {

  // §4.3 결함 — 레이어 무관 방어(내부 호출·컨슈머 경로도 커버).
  public ChargeConfirmCommand {
    if (amount == null || amount <= 0) {
      throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
    }
  }
}
