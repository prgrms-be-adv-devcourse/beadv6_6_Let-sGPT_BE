package com.openat.order.infrastructure.client;

import java.util.UUID;

final class PaymentRefundPortDtos {

  private PaymentRefundPortDtos() {}

  record RefundRequest(UUID orderId) {}

  record RefundResponse(RefundResult result) {}

  enum RefundResult {
    NO_PAYMENT,
    REFUND_ACCEPTED
  }
}
