package com.openat.order.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.openat.order.infrastructure.client.PaymentRefundPortDtos.RefundResponse;
import com.openat.order.infrastructure.client.PaymentRefundPortDtos.RefundResult;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PaymentRefundInternalApiClientTest {

  @Test
  void should_send_t1_draft_contract() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://payment");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    PaymentRefundInternalApiClient client = new PaymentRefundInternalApiClient(builder.build());
    UUID orderId = UUID.randomUUID();
    server
        .expect(once(), requestTo("http://payment/internal/v1/refunds"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Idempotency-Key", "refund-order-" + orderId))
        .andExpect(jsonPath("$.orderId").value(orderId.toString()))
        .andRespond(
            withSuccess(
                "{\"result\":\"REFUND_ACCEPTED\"}",
                org.springframework.http.MediaType.APPLICATION_JSON));

    RefundResponse response = client.requestRefund(orderId, "refund-order-" + orderId);

    assertThat(response.result()).isEqualTo(RefundResult.REFUND_ACCEPTED);
    server.verify();
  }

  @Test
  void should_deserialize_no_payment_response() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://payment");
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    PaymentRefundInternalApiClient client = new PaymentRefundInternalApiClient(builder.build());
    UUID orderId = UUID.randomUUID();
    server
        .expect(once(), requestTo("http://payment/internal/v1/refunds"))
        .andRespond(
            withSuccess(
                "{\"result\":\"NO_PAYMENT\"}",
                org.springframework.http.MediaType.APPLICATION_JSON));

    RefundResponse response = client.requestRefund(orderId, "refund-order-" + orderId);

    assertThat(response.result()).isEqualTo(RefundResult.NO_PAYMENT);
    server.verify();
  }
}
