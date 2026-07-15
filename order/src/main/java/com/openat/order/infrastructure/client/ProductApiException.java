package com.openat.order.infrastructure.client;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientException;

class ProductApiException extends RestClientException {

    private final ProductErrorResponse errorResponse;

    ProductApiException(HttpStatusCode statusCode, ProductErrorResponse errorResponse, String responseBody) {
        super("Product API returned %s: %s".formatted(statusCode, responseBody));
        this.errorResponse = errorResponse;
    }

    ProductErrorResponse getErrorResponse() {
        return errorResponse;
    }
}
