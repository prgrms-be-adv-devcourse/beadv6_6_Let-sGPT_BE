package com.openat.order.infrastructure.client;

import com.openat.order.infrastructure.client.PaymentStatusPortDtos.PaymentStatusResponse;
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
public class PaymentStatusInternalApiClient {

  private final RestClient paymentRestClient;

  public PaymentStatusInternalApiClient(
      @Qualifier("paymentRestClient") RestClient paymentRestClient) {
    this.paymentRestClient = paymentRestClient;
  }

  public PaymentStatusResponse findByOrderId(UUID orderId) {
    PaymentStatusResponse response =
        paymentRestClient
            .get()
            .uri(
                uriBuilder ->
                    uriBuilder.path("/internal/v1/payments").queryParam("orderId", orderId).build())
            .retrieve()
            .onStatus(
                status -> status.value() == 404,
                (request, ignored) -> {
                  throw new PaymentStatusNotFoundException("Payment not found: orderId=" + orderId);
                })
            .onStatus(status -> status.isError(), this::throwPaymentApiException)
            .body(PaymentStatusResponse.class);
    if (response == null || response.status() == null) {
      throw new RestClientException("Payment status response body is empty: orderId=" + orderId);
    }
    return response;
  }

  private void throwPaymentApiException(HttpRequest request, ClientHttpResponse response)
      throws IOException {
    String body = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
    throw new PaymentStatusApiException(response.getStatusCode(), body);
  }
}
