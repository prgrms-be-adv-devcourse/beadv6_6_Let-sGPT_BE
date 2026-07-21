package com.openat.order.application.port;

public class PaymentStatusPortException extends RuntimeException {

  public PaymentStatusPortException(String message, Throwable cause) {
    super(message, cause);
  }
}
