package com.openat.order.infrastructure.client;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientException;

public class PaymentStatusApiException extends RestClientException {

  private final HttpStatusCode statusCode;

  PaymentStatusApiException(HttpStatusCode statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public boolean isServerError() {
    return statusCode.is5xxServerError();
  }
}
