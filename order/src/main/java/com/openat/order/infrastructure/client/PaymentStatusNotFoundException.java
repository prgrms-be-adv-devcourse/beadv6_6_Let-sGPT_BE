package com.openat.order.infrastructure.client;

import org.springframework.web.client.RestClientException;

class PaymentStatusNotFoundException extends RestClientException {

  PaymentStatusNotFoundException(String message) {
    super(message);
  }
}
