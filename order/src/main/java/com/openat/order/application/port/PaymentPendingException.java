package com.openat.order.application.port;

public class PaymentPendingException extends PaymentRefundPortException {

  public PaymentPendingException(String message, Throwable cause) {
    super(message, cause);
  }
}
