package com.openat.order.infrastructure.client;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientException;

public class PaymentRefundApiException extends RestClientException {

  private final HttpStatusCode statusCode;

  PaymentRefundApiException(HttpStatusCode statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public boolean isServerError() {
    return statusCode.is5xxServerError();
  }

  public boolean isConflict() {
    return statusCode.value() == 409;
  }
}
