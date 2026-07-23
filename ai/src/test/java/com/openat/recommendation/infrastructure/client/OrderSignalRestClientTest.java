package com.openat.recommendation.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class OrderSignalRestClientTest {

  private static final String BASE_URL = "http://order-service";
  private MockRestServiceServer server;
  private OrderSignalRestClient client;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
    server = MockRestServiceServer.bindTo(builder).build();
    client = new OrderSignalRestClient(builder.build(), 20);
  }

  @Test
  @DisplayName("구매 신호 조회는 회원 ID와 20개 제한을 order API 쿼리로 전달한다")
  void getPurchaseSignals_withMember_forwardsExpectedQueryParameters() {
    UUID memberId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                BASE_URL
                    + "/internal/v1/orders/purchase-signals?memberId="
                    + memberId
                    + "&limit=20"))
        .andExpect(method(HttpMethod.GET))
        .andRespond(
            withSuccess(
                """
                        [{"productId":"%s","orderCount":2,"totalQuantity":3,
                          "lastOrderedAt":"2026-07-15T10:00:00Z"}]
                        """
                    .formatted(productId),
                MediaType.APPLICATION_JSON));

    var result = client.getPurchaseSignals(memberId);

    assertThat(result)
        .singleElement()
        .satisfies(
            signal -> {
              assertThat(signal.productId()).isEqualTo(productId);
              assertThat(signal.orderCount()).isEqualTo(2);
              assertThat(signal.totalQuantity()).isEqualTo(3);
              assertThat(signal.lastOrderedAt()).isEqualTo(Instant.parse("2026-07-15T10:00:00Z"));
            });
    server.verify();
  }

  @Test
  @DisplayName("구매 신호 조회 응답 본문이 비어 있으면 예외를 던진다")
  void getPurchaseSignals_withEmptyResponseBody_throwsRestClientException() {
    UUID memberId = UUID.randomUUID();
    server
        .expect(
            requestTo(
                BASE_URL
                    + "/internal/v1/orders/purchase-signals?memberId="
                    + memberId
                    + "&limit=20"))
        .andRespond(withSuccess());

    assertThatThrownBy(() -> client.getPurchaseSignals(memberId))
        .isInstanceOf(RestClientException.class)
        .hasMessage("Order purchase signal response body is empty");
    server.verify();
  }
}
