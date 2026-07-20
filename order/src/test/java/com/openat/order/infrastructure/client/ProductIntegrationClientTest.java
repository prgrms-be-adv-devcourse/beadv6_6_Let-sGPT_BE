package com.openat.order.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.application.dto.StockDecreaseCommand;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.event.StockAdjustment;
import com.openat.order.application.event.StockAdjustmentReason;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.infrastructure.client.ProductPortDtos.OrderSnapshotResponse;
import com.openat.order.infrastructure.client.ProductPortDtos.StockChangeRequest;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class ProductIntegrationClientTest {

  @Mock private ProductInternalApiClient productInternalApiClient;

  @Mock private RetrySleeper retrySleeper;

  @Mock private ApplicationEventPublisher applicationEventPublisher;

  private ProductIntegrationClient productIntegrationClient;
  private CircuitBreaker circuitBreaker;

  @BeforeEach
  void setUp() {
    circuitBreaker = CircuitBreaker.ofDefaults("product-test");
    productIntegrationClient =
        new ProductIntegrationClient(
            productInternalApiClient, retrySleeper, circuitBreaker, applicationEventPublisher);
  }

  @Test
  @DisplayName("상품 주문 기준정보 응답은 productId, sellerId, unitPrice만 주문 생성에 사용한다")
  void fetchOrderSnapshot_mapsMinimalOrderBasis() {
    UUID dropId = UUID.randomUUID();
    UUID productId = UUID.randomUUID();
    UUID sellerId = UUID.randomUUID();

    when(productInternalApiClient.fetchOrderSnapshot(dropId))
        .thenReturn(new OrderSnapshotResponse(productId, sellerId, 10_000L, "스냅샷 상품"));

    var result = productIntegrationClient.fetchOrderSnapshot(dropId);

    assertThat(result.productId()).isEqualTo(productId);
    assertThat(result.sellerId()).isEqualTo(sellerId);
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
    ArgumentCaptor<StockAdjustment> adjustment = ArgumentCaptor.forClass(StockAdjustment.class);
    verify(applicationEventPublisher).publishEvent(adjustment.capture());
    assertThat(adjustment.getValue().dropId()).isEqualTo(dropId);
    assertThat(adjustment.getValue().count()).isEqualTo(2);
    assertThat(adjustment.getValue().reason()).isEqualTo(StockAdjustmentReason.CREATED);
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
    ArgumentCaptor<StockAdjustment> adjustment = ArgumentCaptor.forClass(StockAdjustment.class);
    verify(applicationEventPublisher).publishEvent(adjustment.capture());
    assertThat(adjustment.getValue().dropId()).isEqualTo(dropId);
    assertThat(adjustment.getValue().count()).isEqualTo(2);
    assertThat(adjustment.getValue().reason()).isEqualTo(StockAdjustmentReason.CANCELLED);
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
    ProductApiException exception =
        new ProductApiException(
            HttpStatus.CONFLICT,
            new ProductErrorResponse(null, productError, "상품 재고 처리 실패"),
            "product error");
    doThrow(exception).when(productInternalApiClient).decreaseStock(any(), any());

    assertThatThrownBy(
            () ->
                productIntegrationClient.decreaseStock(
                    UUID.randomUUID(),
                    new StockDecreaseCommand(UUID.randomUUID(), UUID.randomUUID(), 1)))
        .isInstanceOfSatisfying(
            ProductPortException.class,
            ex -> assertThat(ex.getFailCode()).isEqualTo(expectedFailCode));
    verify(productInternalApiClient).decreaseStock(any(), any());
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("상품 API 연결 실패는 상품 연동 실패 코드로 변환한다")
  void fetchOrderSnapshot_mapsConnectionFailure() {
    doThrow(new ResourceAccessException("connection refused"))
        .when(productInternalApiClient)
        .fetchOrderSnapshot(any());

    assertThatThrownBy(() -> productIntegrationClient.fetchOrderSnapshot(UUID.randomUUID()))
        .isInstanceOfSatisfying(
            ProductPortException.class,
            exception ->
                assertThat(exception.getFailCode())
                    .isEqualTo(OrderFailCode.PRODUCT_INTEGRATION_FAILED));
    verify(productInternalApiClient, times(3)).fetchOrderSnapshot(any());
  }

  @Test
  void should_retry_snapshot_lookup_twice_when_integration_recovers() throws InterruptedException {
    UUID dropId = UUID.randomUUID();
    OrderSnapshotResponse response =
        new OrderSnapshotResponse(UUID.randomUUID(), UUID.randomUUID(), 10_000L, "상품");
    when(productInternalApiClient.fetchOrderSnapshot(dropId))
        .thenThrow(new ResourceAccessException("timeout"))
        .thenThrow(new ResourceAccessException("timeout"))
        .thenReturn(response);

    assertThat(productIntegrationClient.fetchOrderSnapshot(dropId).productId())
        .isEqualTo(response.productId());

    verify(productInternalApiClient, times(3)).fetchOrderSnapshot(dropId);
    verify(retrySleeper).sleep(500L);
    verify(retrySleeper).sleep(1_000L);
  }

  @Test
  void should_not_retry_snapshot_lookup_on_business_failure() throws InterruptedException {
    ProductApiException exception =
        new ProductApiException(
            HttpStatus.CONFLICT,
            new ProductErrorResponse(null, "DROP_CLOSED", "closed"),
            "product error");
    doThrow(exception).when(productInternalApiClient).fetchOrderSnapshot(any());

    assertThatThrownBy(() -> productIntegrationClient.fetchOrderSnapshot(UUID.randomUUID()))
        .isInstanceOfSatisfying(
            ProductPortException.class,
            failure -> assertThat(failure.getFailCode()).isEqualTo(OrderFailCode.DROP_CLOSED));

    verify(productInternalApiClient).fetchOrderSnapshot(any());
    verify(retrySleeper, never()).sleep(org.mockito.ArgumentMatchers.anyLong());
  }

  @Test
  @DisplayName("재고 롤백 API 연결 실패는 재고 롤백 실패 코드로 변환한다")
  void restoreStock_mapsConnectionFailure() {
    doThrow(new ResourceAccessException("connection refused"))
        .when(productInternalApiClient)
        .restoreStock(any(), any());

    assertThatThrownBy(
            () ->
                productIntegrationClient.restoreStock(
                    UUID.randomUUID(),
                    new StockRestoreCommand(UUID.randomUUID(), UUID.randomUUID(), 1)))
        .isInstanceOfSatisfying(
            ProductPortException.class,
            exception ->
                assertThat(exception.getFailCode()).isEqualTo(OrderFailCode.STOCK_ROLLBACK_FAILED));
    verify(productInternalApiClient, times(3)).restoreStock(any(), any());
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  @Test
  void should_retry_stock_decrease_twice_when_integration_recovers() throws InterruptedException {
    doThrow(new ResourceAccessException("timeout"))
        .doThrow(new ResourceAccessException("timeout"))
        .doNothing()
        .when(productInternalApiClient)
        .decreaseStock(any(), any());

    productIntegrationClient.decreaseStock(
        UUID.randomUUID(), new StockDecreaseCommand(UUID.randomUUID(), UUID.randomUUID(), 1));

    verify(productInternalApiClient, times(3)).decreaseStock(any(), any());
    verify(retrySleeper).sleep(500L);
    verify(retrySleeper).sleep(1_000L);
    verify(applicationEventPublisher, times(1)).publishEvent(any(StockAdjustment.class));
  }

  @Test
  void should_fail_immediately_when_product_circuit_is_open() {
    circuitBreaker.transitionToOpenState();

    assertThatThrownBy(
            () ->
                productIntegrationClient.decreaseStock(
                    UUID.randomUUID(),
                    new StockDecreaseCommand(UUID.randomUUID(), UUID.randomUUID(), 1)))
        .isInstanceOfSatisfying(
            ProductPortException.class,
            exception ->
                assertThat(exception.getFailCode())
                    .isEqualTo(OrderFailCode.PRODUCT_INTEGRATION_FAILED));

    verify(productInternalApiClient, org.mockito.Mockito.never()).decreaseStock(any(), any());
  }
}
