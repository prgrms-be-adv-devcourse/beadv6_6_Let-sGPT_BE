package com.openat.order.domain.model;

public enum OrderStatus {
  PAYMENT_PENDING,
  COMPLETED,
  FAILED,
  CANCELLED,
  CANCEL_REQUESTED,
  // 예약 상태 — 전이 경로 없음, 외부에서 값이 들어오므로 제거 금지
  REFUND_PENDING,
  REFUNDED,
  REFUND_FAILED
}
