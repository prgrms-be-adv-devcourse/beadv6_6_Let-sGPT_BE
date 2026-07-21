package com.openat.order.infrastructure.client;

import java.util.UUID;

final class PaymentStatusPortDtos {

  private PaymentStatusPortDtos() {}

  record PaymentStatusResponse(UUID paymentId, PaymentState status, Long amount) {}

  enum PaymentState {
    PENDING,
    PAYMENT_PENDING,
    APPROVED,
    FAILED,
    CANCELED,
    REFUNDED,
    PARTIALLY_REFUNDED
  }
}
