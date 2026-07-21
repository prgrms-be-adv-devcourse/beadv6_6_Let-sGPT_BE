package com.openat.order.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.application.dto.PaymentStatus;
import com.openat.order.application.dto.PaymentStatusInfo;
import com.openat.order.infrastructure.client.PaymentStatusPortDtos.PaymentState;
import com.openat.order.infrastructure.client.PaymentStatusPortDtos.PaymentStatusResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class PaymentStatusIntegrationClientTest {

  @Mock PaymentStatusInternalApiClient internalApiClient;
  @Mock RetrySleeper retrySleeper;
  PaymentStatusIntegrationClient client;

  @BeforeEach
  void setUp() {
    client =
        new PaymentStatusIntegrationClient(
            internalApiClient, retrySleeper, CircuitBreaker.ofDefaults("payment-status-test"));
  }

  @Test
  void should_map_payment_not_found() {
    UUID orderId = UUID.randomUUID();
    when(internalApiClient.findByOrderId(orderId))
        .thenThrow(new PaymentStatusNotFoundException("not found"));

    PaymentStatusInfo result = client.findByOrderId(orderId);

    assertThat(result.status()).isEqualTo(PaymentStatus.NO_PAYMENT);
    assertThat(result.paymentId()).isNull();
  }

  @Test
  void should_retry_twice_when_server_error() throws InterruptedException {
    UUID orderId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    when(internalApiClient.findByOrderId(orderId))
        .thenThrow(new PaymentStatusApiException(HttpStatus.SERVICE_UNAVAILABLE, "down"))
        .thenThrow(new PaymentStatusApiException(HttpStatus.BAD_GATEWAY, "down"))
        .thenReturn(new PaymentStatusResponse(paymentId, PaymentState.APPROVED, 10_000L));

    PaymentStatusInfo result = client.findByOrderId(orderId);

    assertThat(result.status()).isEqualTo(PaymentStatus.APPROVED);
    assertThat(result.paymentId()).isEqualTo(paymentId);
    assertThat(result.amount()).isEqualTo(10_000L);
    verify(retrySleeper).sleep(500L);
    verify(retrySleeper).sleep(1_000L);
  }
}
