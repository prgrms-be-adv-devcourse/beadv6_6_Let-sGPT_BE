package com.openat.order.application.dto;

public enum PaymentStatus {
  NO_PAYMENT,
  PENDING,
  PAYMENT_PENDING,
  APPROVED,
  FAILED,
  CANCELED,
  REFUNDED,
  PARTIALLY_REFUNDED
}
