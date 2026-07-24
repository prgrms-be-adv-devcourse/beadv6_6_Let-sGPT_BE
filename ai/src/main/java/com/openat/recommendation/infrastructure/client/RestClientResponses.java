package com.openat.recommendation.infrastructure.client;

import org.springframework.web.client.RestClientException;

final class RestClientResponses {

  private RestClientResponses() {}

  static <T> T requireBody(T body, String message) {
    if (body == null) {
      throw new RestClientException(message);
    }
    return body;
  }
}
