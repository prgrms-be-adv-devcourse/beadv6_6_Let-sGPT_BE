package com.openat.order.domain.model;

public enum OrderStatus {
  PAYMENT_PENDING,
  COMPLETED,
  FAILED,
  CANCELLED,
  CANCEL_REQUESTED,
  // order는 이 값을 쓰지 않는다(환불 요청 중 실제 상태는 CANCEL_REQUESTED). 목록 필터·환불 이벤트
  // 처리에서 값이 들어와도 400/조회 실패로 떨어지지 않게 받아만 두는 예약 상태다. 제거하려면
  // FE 상태 필터·ai 모듈 OrderStatus·orders_status_check 제약까지 같이 정리해야 한다.
  REFUND_PENDING,
  REFUNDED,
  REFUND_FAILED
}
