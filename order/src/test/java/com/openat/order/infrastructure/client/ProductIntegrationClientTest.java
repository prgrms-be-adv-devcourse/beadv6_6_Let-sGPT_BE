package com.openat.order.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.application.dto.StockDecreaseCommand;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.infrastructure.client.ProductPortDtos.OrderSnapshotResponse;
import com.openat.order.infrastructure.client.ProductPortDtos.StockChangeRequest;
import feign.FeignException;
import feign.Request;
import feign.Response;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

@ExtendWith(MockitoExtension.class)
class ProductIntegrationClientTest {

    @Mock
    private ProductInternalApiClient productInternalApiClient;

    @InjectMocks
    private ProductIntegrationClient productIntegrationClient;

    @Test
    @DisplayName("상품 주문 기준정보 내부 API 경로는 order-snapshot을 사용한다")
    void fetchOrderSnapshot_mappingPath() throws Exception {
        Method method = ProductInternalApiClient.class.getMethod("fetchOrderSnapshot", UUID.class);

        GetMapping getMapping = method.getAnnotation(GetMapping.class);

        assertThat(getMapping.value()).containsExactly("/internal/drops/{dropId}/order-snapshot");
    }

    @Test
    @DisplayName("상품 재고 차감 내부 API 경로는 stock-deductions를 사용한다")
    void decreaseStock_mappingPath() throws Exception {
        Method method = ProductInternalApiClient.class.getMethod("decreaseStock", UUID.class, StockChangeRequest.class);

        PostMapping postMapping = method.getAnnotation(PostMapping.class);

        assertThat(postMapping.value()).containsExactly("/internal/drops/{dropId}/stock-deductions");
    }

    @Test
    @DisplayName("상품 재고 롤백 내부 API 경로는 stock-rollbacks를 사용한다")
    void restoreStock_mappingPath() throws Exception {
        Method method = ProductInternalApiClient.class.getMethod("restoreStock", UUID.class, StockChangeRequest.class);

        PostMapping postMapping = method.getAnnotation(PostMapping.class);

        assertThat(postMapping.value()).containsExactly("/internal/drops/{dropId}/stock-rollbacks");
    }

    @Test
    @DisplayName("상품 주문 기준정보 응답은 productId, sellerId, productName, unitPrice를 주문 생성에 사용한다")
    void fetchOrderSnapshot_mapsMinimalOrderBasis() {
        UUID dropId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        UUID sellerId = UUID.randomUUID();
        String productName = "테스트 상품";

        when(productInternalApiClient.fetchOrderSnapshot(dropId))
                .thenReturn(new OrderSnapshotResponse(productId, sellerId, productName, 10_000L));

        var result = productIntegrationClient.fetchOrderSnapshot(dropId);

        assertThat(result.productId()).isEqualTo(productId);
        assertThat(result.sellerId()).isEqualTo(sellerId);
        assertThat(result.productName()).isEqualTo(productName);
        assertThat(result.unitPrice()).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("재고 차감 요청은 orderId, buyerId, quantity를 body로 전달한다")
    void decreaseStock_sendsProductStockChangeRequest() {
        UUID dropId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();

        productIntegrationClient.decreaseStock(dropId, new StockDecreaseCommand(orderId, buyerId, 2));

        ArgumentCaptor<StockChangeRequest> request = ArgumentCaptor.forClass(StockChangeRequest.class);
        verify(productInternalApiClient).decreaseStock(eq(dropId), request.capture());
        assertThat(request.getValue().orderId()).isEqualTo(orderId);
        assertThat(request.getValue().buyerId()).isEqualTo(buyerId);
        assertThat(request.getValue().quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("재고 롤백 요청은 orderId, buyerId, quantity를 body로 전달한다")
    void restoreStock_sendsProductStockChangeRequest() {
        UUID dropId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID buyerId = UUID.randomUUID();

        productIntegrationClient.restoreStock(dropId, new StockRestoreCommand(orderId, buyerId, 2));

        ArgumentCaptor<StockChangeRequest> request = ArgumentCaptor.forClass(StockChangeRequest.class);
        verify(productInternalApiClient).restoreStock(eq(dropId), request.capture());
        assertThat(request.getValue().orderId()).isEqualTo(orderId);
        assertThat(request.getValue().buyerId()).isEqualTo(buyerId);
        assertThat(request.getValue().quantity()).isEqualTo(2);
    }

    @ParameterizedTest
    @CsvSource({
            "DROP_SOLD_OUT, SOLD_OUT",
            "DROP_NOT_OPEN, DROP_NOT_OPEN",
            "DROP_CLOSED, DROP_CLOSED",
            "DROP_LIMIT_EXCEEDED, LIMIT_EXCEEDED"
    })
    @DisplayName("상품 재고 차감 비즈니스 에러는 주문 실패 코드로 변환한다")
    void decreaseStock_mapsProductBusinessError(String productError, OrderFailCode expectedFailCode) {
        FeignException exception = feignException(409, """
                {"error":"%s","message":"상품 재고 처리 실패"}
                """.formatted(productError));
        doThrow(exception).when(productInternalApiClient).decreaseStock(any(), any());

        assertThatThrownBy(() -> productIntegrationClient.decreaseStock(
                        UUID.randomUUID(),
                        new StockDecreaseCommand(UUID.randomUUID(), UUID.randomUUID(), 1)
                ))
                .isInstanceOfSatisfying(ProductPortException.class, ex ->
                        assertThat(ex.getFailCode()).isEqualTo(expectedFailCode));
    }

    private FeignException feignException(int status, String body) {
        Request request = Request.create(
                Request.HttpMethod.POST,
                "/internal/drops/test/stock-deductions",
                Map.of(),
                null,
                StandardCharsets.UTF_8
        );
        Response response = Response.builder()
                .status(status)
                .reason("error")
                .request(request)
                .body(body, StandardCharsets.UTF_8)
                .build();
        return FeignException.errorStatus("product", response);
    }
}
