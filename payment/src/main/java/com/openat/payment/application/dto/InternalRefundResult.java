package com.openat.payment.application.dto;

// order 취소 사가의 내부 환불 진입점(POST /internal/v1/refunds) 결과값.
// NO_PAYMENT/REFUND_ACCEPTED은 200, PAYMENT_PENDING은 409로 매핑된다(InternalRefundController).
public enum InternalRefundResult {
  // 결제 성사분 없음(row 없음 또는 전부 PENDING/FAILED/CANCELED).
  NO_PAYMENT,
  // 환불 접수 보장(완료 통지는 기존 refund-completed Kafka 이벤트). 이미 REFUNDED거나 멱등 재시도도 이 값으로 재생.
  REFUND_ACCEPTED,
  // 결제 진행 중(PAYMENT_PENDING) — NO_PAYMENT로 답하면 직후 confirm 성사 시 결제만 살아남는 레이스. order는 재시도/폴링.
  PAYMENT_PENDING
}
