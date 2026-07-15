package com.openat.payment.application.dto;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.exception.PaymentErrorCode;
import java.util.UUID;

// PG confirm 단계 입력(A16) — paymentKey는 브라우저가 토스 SDK로 직접 발급받아 successUrl로 전달받은 값.
// 7-13 plan D4 — Idempotency-Key 헤더 폐지, 토스 아웃바운드 멱등 헤더도 paymentKey를 그대로 사용.
public record PgConfirmCommand(UUID orderId, UUID memberId, Long amount, String paymentKey) {

  // §4.3 결함 — 레이어 무관 방어(내부 호출·컨슈머 경로도 커버).
  public PgConfirmCommand {
    if (amount == null || amount <= 0) {
      throw new BusinessException(PaymentErrorCode.INVALID_AMOUNT);
    }
  }
}
