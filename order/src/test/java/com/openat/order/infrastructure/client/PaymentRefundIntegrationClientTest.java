package com.openat.order.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.application.dto.PaymentRefundResult;
import com.openat.order.application.port.PaymentRefundPortException;
import com.openat.order.infrastructure.client.PaymentRefundPortDtos.RefundResponse;
import com.openat.order.infrastructure.client.PaymentRefundPortDtos.RefundResult;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class PaymentRefundIntegrationClientTest {

  @Mock PaymentRefundInternalApiClient internalApiClient;
  @Mock RetrySleeper retrySleeper;
  PaymentRefundIntegrationClient client;

  @BeforeEach
  void setUp() {
    client =
        new PaymentRefundIntegrationClient(
            internalApiClient, retrySleeper, CircuitBreaker.ofDefaults("payment-refund-test"));
  }

  @Test
  void should_map_no_payment_response_to_payment_not_completed() {
    UUID orderId = UUID.randomUUID();
    when(internalApiClient.requestRefund(orderId, "key"))
        .thenReturn(new RefundResponse(RefundResult.NO_PAYMENT));

    assertThat(client.requestRefund(orderId, "key"))
        .isEqualTo(PaymentRefundResult.PAYMENT_NOT_COMPLETED);
  }

  @Test
  void should_retry_twice_when_server_error() throws InterruptedException {
    UUID orderId = UUID.randomUUID();
    when(internalApiClient.requestRefund(orderId, "key"))
        .thenThrow(new PaymentRefundApiException(HttpStatus.SERVICE_UNAVAILABLE, "down"))
        .thenThrow(new PaymentRefundApiException(HttpStatus.BAD_GATEWAY, "down"))
        .thenReturn(new RefundResponse(RefundResult.REFUND_ACCEPTED));

    assertThat(client.requestRefund(orderId, "key")).isEqualTo(PaymentRefundResult.REFUND_ACCEPTED);
    verify(retrySleeper).sleep(500L);
    verify(retrySleeper).sleep(1_000L);
    verify(internalApiClient, times(3)).requestRefund(orderId, "key");
  }

  @Test
  void should_not_retry_when_client_error() throws InterruptedException {
    UUID orderId = UUID.randomUUID();
    when(internalApiClient.requestRefund(orderId, "key"))
        .thenThrow(new PaymentRefundApiException(HttpStatus.CONFLICT, "conflict"));

    assertThatThrownBy(() -> client.requestRefund(orderId, "key"))
        .isInstanceOf(PaymentRefundPortException.class);
    verify(retrySleeper, times(0)).sleep(500L);
    verify(internalApiClient).requestRefund(orderId, "key");
  }
}
