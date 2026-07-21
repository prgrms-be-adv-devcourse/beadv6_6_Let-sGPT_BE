package com.openat.order.infrastructure.client;

import com.openat.order.infrastructure.client.PaymentRefundPortDtos.RefundRequest;
import com.openat.order.infrastructure.client.PaymentRefundPortDtos.RefundResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class PaymentRefundInternalApiClient {

  private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  private final RestClient paymentRestClient;

  public PaymentRefundInternalApiClient(
      @Qualifier("paymentRestClient") RestClient paymentRestClient) {
    this.paymentRestClient = paymentRestClient;
  }

  public RefundResponse requestRefund(UUID orderId, String idempotencyKey) {
    RefundResponse response =
        paymentRestClient
            .post()
            .uri("/internal/v1/refunds")
            .header(IDEMPOTENCY_KEY_HEADER, idempotencyKey)
            .body(new RefundRequest(orderId))
            .retrieve()
            .onStatus(status -> status.isError(), this::throwPaymentApiException)
            .body(RefundResponse.class);
    if (response == null || response.result() == null) {
      throw new RestClientException("Payment refund response body is empty: orderId=" + orderId);
    }
    return response;
  }

  private void throwPaymentApiException(HttpRequest request, ClientHttpResponse response)
      throws IOException {
    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
    throw new PaymentRefundApiException(response.getStatusCode(), body);
  }
}
