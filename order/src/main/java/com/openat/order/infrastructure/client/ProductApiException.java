package com.openat.order.infrastructure.client;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientException;

public class ProductApiException extends RestClientException {

  private final ProductErrorResponse errorResponse;
  private final HttpStatusCode statusCode;

  ProductApiException(
      HttpStatusCode statusCode, ProductErrorResponse errorResponse, String responseBody) {
    super("Product API returned %s: %s".formatted(statusCode, responseBody));
    this.errorResponse = errorResponse;
    this.statusCode = statusCode;
  }

  ProductErrorResponse getErrorResponse() {
    return errorResponse;
  }

  public boolean isServerError() {
    return statusCode.is5xxServerError();
  }
}
