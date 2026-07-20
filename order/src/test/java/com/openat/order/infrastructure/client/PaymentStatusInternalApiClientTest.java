package com.openat.order.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.openat.order.infrastructure.client.PaymentStatusPortDtos.PaymentState;
import com.openat.order.infrastructure.client.PaymentStatusPortDtos.PaymentStatusResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PaymentStatusInternalApiClientTest {

  @Test
  void should_send_payment_status_draft_contract() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://payment");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    PaymentStatusInternalApiClient client = new PaymentStatusInternalApiClient(builder.build());
    UUID orderId = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    server
        .expect(once(), requestTo("http://payment/internal/v1/payments?orderId=" + orderId))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                "{\"paymentId\":\"%s\",\"status\":\"APPROVED\",\"amount\":10000}"
                    .formatted(paymentId),
                MediaType.APPLICATION_JSON));

    PaymentStatusResponse response = client.findByOrderId(orderId);

    assertThat(response.paymentId()).isEqualTo(paymentId);
    assertThat(response.status()).isEqualTo(PaymentState.APPROVED);
    assertThat(response.amount()).isEqualTo(10_000L);
    server.verify();
  }
}
