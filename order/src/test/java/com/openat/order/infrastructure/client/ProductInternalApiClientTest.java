package com.openat.order.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.infrastructure.client.ProductPortDtos.StockChangeRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class ProductInternalApiClientTest {

    private static final String BASE_URL = "http://product-service";

    private MockRestServiceServer server;
    private ProductInternalApiClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new ProductInternalApiClient(builder.build(), new ObjectMapper());
    }

    @Test
    @DisplayName("상품 주문 기준정보를 order-snapshot 경로에서 조회한다")
    void fetchOrderSnapshot_usesExpectedEndpoint() {
        UUID dropId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        server.expect(requestTo(BASE_URL + "/internal/drops/" + dropId + "/order-snapshot"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"productId":"%s","sellerId":"%s","unitPrice":10000}
                        """.formatted(productId, sellerId), MediaType.APPLICATION_JSON));

        var response = client.fetchOrderSnapshot(dropId);

        assertThat(response.productId()).isEqualTo(productId);
        assertThat(response.sellerId()).isEqualTo(sellerId);
        assertThat(response.unitPrice()).isEqualTo(10_000L);
        server.verify();
    }

    @Test
    @DisplayName("상품 주문 기준정보 응답 바디가 비어 있으면 명시적인 연동 예외가 발생한다")
    void fetchOrderSnapshot_whenResponseBodyIsEmpty_throwsRestClientException() {
        UUID dropId = UUID.randomUUID();
        server.expect(requestTo(BASE_URL + "/internal/drops/" + dropId + "/order-snapshot"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess());

        assertThatThrownBy(() -> client.fetchOrderSnapshot(dropId))
                .isInstanceOf(RestClientException.class)
                .hasMessage("Product order snapshot response body is empty: dropId=" + dropId);
        server.verify();
    }

    @Test
    @DisplayName("재고 차감 요청을 stock-deductions 경로에 기존 body로 전송한다")
    void decreaseStock_usesExpectedEndpointAndBody() {
        UUID dropId = UUID.randomUUID();
        StockChangeRequest request = new StockChangeRequest(UUID.randomUUID(), UUID.randomUUID(), 2);
        server.expect(requestTo(BASE_URL + "/internal/drops/" + dropId + "/stock-deductions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"orderId":"%s","buyerId":"%s","quantity":2}
                        """.formatted(request.orderId(), request.buyerId())))
                .andRespond(withNoContent());

        client.decreaseStock(dropId, request);

        server.verify();
    }

    @Test
    @DisplayName("재고 롤백 요청을 stock-rollbacks 경로에 전송한다")
    void restoreStock_usesExpectedEndpoint() {
        UUID dropId = UUID.randomUUID();
        StockChangeRequest request = new StockChangeRequest(UUID.randomUUID(), UUID.randomUUID(), 2);
        server.expect(requestTo(BASE_URL + "/internal/drops/" + dropId + "/stock-rollbacks"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());

        client.restoreStock(dropId, request);

        server.verify();
    }

    @Test
    @DisplayName("상품 API 오류 응답의 코드와 메시지를 보존한다")
    void decreaseStock_preservesProductErrorResponse() {
        UUID dropId = UUID.randomUUID();
        StockChangeRequest request = new StockChangeRequest(UUID.randomUUID(), UUID.randomUUID(), 1);
        server.expect(requestTo(BASE_URL + "/internal/drops/" + dropId + "/stock-deductions"))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {"error":"DROP_SOLD_OUT","message":"재고가 없습니다"}
                                """));

        assertThatThrownBy(() -> client.decreaseStock(dropId, request))
                .isInstanceOfSatisfying(ProductApiException.class, exception -> {
                    assertThat(exception.getErrorResponse().failCode()).isEqualTo("DROP_SOLD_OUT");
                    assertThat(exception.getErrorResponse().message()).isEqualTo("재고가 없습니다");
                });
        server.verify();
    }
}
