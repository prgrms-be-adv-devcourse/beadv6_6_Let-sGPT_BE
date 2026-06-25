package com.openat.order.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.application.dto.StockDecreaseCommand;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.model.OrderFailCode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@DisplayName("상품 연동 어댑터")
class ProductIntegrationPortAdapterTest {

    private static final String PRODUCT_SERVICE_URL = "http://product-service";

    private final UUID dropId = UUID.fromString("01973588-1111-7000-8000-000000000001");
    private final UUID orderId = UUID.fromString("01973588-2222-7000-8000-000000000002");

    private MockRestServiceServer server;
    private ProductIntegrationPortAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
        adapter = new ProductIntegrationPortAdapter(restClientBuilder, new ObjectMapper(), PRODUCT_SERVICE_URL);
    }

    @Nested
    @DisplayName("상품 실패 코드 매핑")
    class FailureMapping {

        @Test
        @DisplayName("DROP_NOT_OPEN 응답은 주문 실패 코드 DROP_NOT_OPEN으로 변환한다")
        void decreaseStock_dropNotOpen_mapsToDropNotOpen() {
            // given
            respondStockDecrease(HttpStatus.BAD_REQUEST, """
                    {"error":"DROP_NOT_OPEN","message":"아직 오픈 전입니다."}
                    """);

            // when & then
            assertThatExceptionOfType(ProductPortException.class)
                    .isThrownBy(() -> adapter.decreaseStock(dropId, decreaseCommand()))
                    .satisfies(e -> {
                        assertThat(e.getFailCode()).isEqualTo(OrderFailCode.DROP_NOT_OPEN);
                        assertThat(e.getMessage()).isEqualTo("아직 오픈 전입니다.");
                    });

            server.verify();
        }

        @Test
        @DisplayName("NOT_OPEN 응답도 주문 실패 코드 DROP_NOT_OPEN으로 정규화한다")
        void decreaseStock_notOpen_mapsToDropNotOpen() {
            // given
            respondStockDecrease(HttpStatus.BAD_REQUEST, """
                    {"error":"NOT_OPEN","message":"아직 오픈 전입니다."}
                    """);

            // when & then
            assertThatExceptionOfType(ProductPortException.class)
                    .isThrownBy(() -> adapter.decreaseStock(dropId, decreaseCommand()))
                    .satisfies(e -> assertThat(e.getFailCode()).isEqualTo(OrderFailCode.DROP_NOT_OPEN));

            server.verify();
        }

        @Test
        @DisplayName("SOLD_OUT 응답은 주문 실패 코드 SOLD_OUT으로 변환한다")
        void decreaseStock_soldOut_mapsToSoldOut() {
            // given
            respondStockDecrease(HttpStatus.CONFLICT, """
                    {"error":"SOLD_OUT","message":"재고가 없습니다."}
                    """);

            // when & then
            assertThatExceptionOfType(ProductPortException.class)
                    .isThrownBy(() -> adapter.decreaseStock(dropId, decreaseCommand()))
                    .satisfies(e -> assertThat(e.getFailCode()).isEqualTo(OrderFailCode.SOLD_OUT));

            server.verify();
        }

        @Test
        @DisplayName("LIMIT_EXCEEDED 응답은 주문 실패 코드 LIMIT_EXCEEDED로 변환한다")
        void decreaseStock_limitExceeded_mapsToLimitExceeded() {
            // given
            respondStockDecrease(HttpStatus.BAD_REQUEST, """
                    {"error":"LIMIT_EXCEEDED","message":"구매 한도를 초과했습니다."}
                    """);

            // when & then
            assertThatExceptionOfType(ProductPortException.class)
                    .isThrownBy(() -> adapter.decreaseStock(dropId, decreaseCommand()))
                    .satisfies(e -> assertThat(e.getFailCode()).isEqualTo(OrderFailCode.LIMIT_EXCEEDED));

            server.verify();
        }

        @Test
        @DisplayName("스냅샷 조회 중 알 수 없는 실패 코드는 PAYMENT_FAILED로 변환한다")
        void fetchOrderSnapshot_unknownError_mapsToPaymentFailed() {
            // given
            server.expect(requestTo(PRODUCT_SERVICE_URL + "/internal/drops/" + dropId + "/order-snapshot"))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("""
                                    {"error":"UNKNOWN_ERROR","message":"상품 조회 실패"}
                                    """));

            // when & then
            assertThatExceptionOfType(ProductPortException.class)
                    .isThrownBy(() -> adapter.fetchOrderSnapshot(dropId))
                    .satisfies(e -> assertThat(e.getFailCode()).isEqualTo(OrderFailCode.PAYMENT_FAILED));

            server.verify();
        }

        @Test
        @DisplayName("재고 감소 중 알 수 없는 실패 코드는 PAYMENT_FAILED로 변환한다")
        void decreaseStock_unknownError_mapsToPaymentFailed() {
            // given
            respondStockDecrease(HttpStatus.INTERNAL_SERVER_ERROR, """
                    {"error":"UNKNOWN_ERROR","message":"재고 감소 실패"}
                    """);

            // when & then
            assertThatExceptionOfType(ProductPortException.class)
                    .isThrownBy(() -> adapter.decreaseStock(dropId, decreaseCommand()))
                    .satisfies(e -> assertThat(e.getFailCode()).isEqualTo(OrderFailCode.PAYMENT_FAILED));

            server.verify();
        }

        @Test
        @DisplayName("재고 롤백 중 알 수 없는 실패 코드는 STOCK_ROLLBACK_FAILED로 변환한다")
        void restoreStock_unknownError_mapsToStockRollbackFailed() {
            // given
            server.expect(requestTo(PRODUCT_SERVICE_URL + "/internal/drops/" + dropId + "/stock/restore"))
                    .andExpect(method(HttpMethod.POST))
                    .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body("""
                                    {"error":"UNKNOWN_ERROR","message":"재고 롤백 실패"}
                                    """));

            // when & then
            assertThatExceptionOfType(ProductPortException.class)
                    .isThrownBy(() -> adapter.restoreStock(dropId, restoreCommand()))
                    .satisfies(e -> assertThat(e.getFailCode()).isEqualTo(OrderFailCode.STOCK_ROLLBACK_FAILED));

            server.verify();
        }
    }

    private void respondStockDecrease(HttpStatus status, String body) {
        server.expect(requestTo(PRODUCT_SERVICE_URL + "/internal/drops/" + dropId + "/stock/decrease"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));
    }

    private StockDecreaseCommand decreaseCommand() {
        return new StockDecreaseCommand(orderId, 1, "idem-001");
    }

    private StockRestoreCommand restoreCommand() {
        return new StockRestoreCommand(orderId, 1, "restore-" + orderId);
    }
}
