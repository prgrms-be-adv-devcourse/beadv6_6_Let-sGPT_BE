package com.openat.order.application.port;

public class PaymentRefundPortException extends RuntimeException {

  public PaymentRefundPortException(String message, Throwable cause) {
    super(message, cause);
  }
}
