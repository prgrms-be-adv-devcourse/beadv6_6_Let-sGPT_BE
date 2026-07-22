package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.application.event.RefundStockRestoreRequested;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderSagaState;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderHistoryRepository;
import com.openat.order.domain.repository.OrderRepository;
import com.openat.order.domain.repository.OrderSagaStateRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderCompensationTransitionServiceTest {

  @Mock OrderRepository orderRepository;
  @Mock OrderSagaStateRepository orderSagaStateRepository;
  @Mock OrderHistoryRepository orderHistoryRepository;
  @Mock OrderHistoryRecorder orderHistoryRecorder;
  @Mock OrderSagaRecorder orderSagaRecorder;
  @Mock ApplicationEventPublisher applicationEventPublisher;
  @InjectMocks OrderCompensationTransitionService service;

  @Test
  void should_complete_product_integration_rollback_without_clearing_failure() {
    Order order = failedOrder(OrderFailCode.PRODUCT_INTEGRATION_FAILED);
    stubCompensating(order);

    assertThat(service.stockRollbackRetryTarget(order.getId())).isSameAs(order);
    service.completeStockRollbackRetry(order.getId());

    assertThat(order.getFailCode()).isEqualTo(OrderFailCode.PRODUCT_INTEGRATION_FAILED);
    verify(orderSagaRecorder).recordCompensationCompleted(order.getId());
    verify(orderHistoryRecorder)
        .record(
            order,
            order.getStatus(),
            "STOCK_ROLLBACK_RETRY_COMPLETED",
            "재고 롤백 재처리 성공",
            "stock-rollback-retry-completed-" + order.getId());
  }

  @Test
  void should_clear_stock_rollback_failure_when_retry_completes() {
    Order order = failedOrder(OrderFailCode.STOCK_ROLLBACK_FAILED);
    stubCompensating(order);

    assertThat(service.stockRollbackRetryTarget(order.getId())).isSameAs(order);
    service.completeStockRollbackRetry(order.getId());

    assertThat(order.getFailCode()).isNull();
    assertThat(order.getFailMessage()).isNull();
    verify(orderSagaRecorder).recordCompensationCompleted(order.getId());
  }

  @Test
  void should_request_stock_restore_when_confirming_refund_from_refund_failed() {
    Order order = failedOrder(OrderFailCode.PG_ERROR);
    ReflectionTestUtils.setField(order, "status", OrderStatus.REFUND_FAILED);
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    service.confirmRefund(order.getId());

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    verify(orderSagaRecorder).recordCompensating(order.getId());
    verify(applicationEventPublisher)
        .publishEvent(
            new RefundStockRestoreRequested(
                order.getId(), order.getDropId(), order.getMemberId(), order.getQuantity()));
  }

  @Test
  void should_skip_stock_restore_when_confirming_refund_after_compensation_completed() {
    Order order = failedOrder(OrderFailCode.PG_ERROR);
    ReflectionTestUtils.setField(order, "status", OrderStatus.REFUND_FAILED);
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    when(orderSagaRecorder.isCompensationCompleted(order.getId())).thenReturn(true);

    service.confirmRefund(order.getId());

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    verify(orderSagaRecorder, never()).recordCompensating(any());
    verify(applicationEventPublisher, never()).publishEvent(any(RefundStockRestoreRequested.class));
  }

  @Test
  void should_not_request_stock_restore_when_confirming_refund_from_cancelled() {
    Order order = failedOrder(OrderFailCode.STOCK_ROLLBACK_FAILED);
    ReflectionTestUtils.setField(order, "status", OrderStatus.CANCELLED);
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    service.confirmRefund(order.getId());

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    verify(applicationEventPublisher, never()).publishEvent(any(RefundStockRestoreRequested.class));
  }

  @Test
  void should_allow_payment_expired_compensation_without_stock_failure_code() {
    Order order = failedOrder(OrderFailCode.PAYMENT_EXPIRED);
    stubCompensating(order);

    assertThat(service.stockRollbackRetryTarget(order.getId())).isSameAs(order);
    service.completeStockRollbackRetry(order.getId());

    assertThat(order.getFailCode()).isEqualTo(OrderFailCode.PAYMENT_EXPIRED);
    verify(orderSagaRecorder).recordCompensationCompleted(order.getId());
  }

  @Test
  void should_allow_compensation_with_null_failure_code() {
    Order order = failedOrder(OrderFailCode.PAYMENT_EXPIRED);
    order.clearFailure();
    stubCompensating(order);

    assertThat(service.stockRollbackRetryTarget(order.getId())).isSameAs(order);
    service.completeStockRollbackRetry(order.getId());

    verify(orderSagaRecorder).recordCompensationCompleted(order.getId());
  }

  @Test
  void should_allow_stock_rollback_retry_for_cancelled_order() {
    Order order = failedOrder(OrderFailCode.PAYMENT_EXPIRED);
    ReflectionTestUtils.setField(order, "status", OrderStatus.CANCELLED);
    stubCompensating(order);

    assertThat(service.stockRollbackRetryTarget(order.getId())).isSameAs(order);
    service.completeStockRollbackRetry(order.getId());

    verify(orderSagaRecorder).recordCompensationCompleted(order.getId());
  }

  @Test
  void should_allow_stock_rollback_retry_for_refunded_order() {
    Order order = failedOrder(OrderFailCode.PAYMENT_EXPIRED);
    ReflectionTestUtils.setField(order, "status", OrderStatus.REFUNDED);
    stubCompensating(order);

    assertThat(service.stockRollbackRetryTarget(order.getId())).isSameAs(order);
    service.completeStockRollbackRetry(order.getId());

    verify(orderSagaRecorder).recordCompensationCompleted(order.getId());
  }

  @Test
  void should_reject_stock_rollback_retry_for_cancel_requested_order() {
    Order order = failedOrder(OrderFailCode.REFUND_REQUEST_FAILED);
    ReflectionTestUtils.setField(order, "status", OrderStatus.CANCEL_REQUESTED);
    stubCompensating(order);

    assertThatThrownBy(() -> service.stockRollbackRetryTarget(order.getId()))
        .isInstanceOf(com.openat.common.exception.BusinessException.class);
    assertThatThrownBy(() -> service.completeStockRollbackRetry(order.getId()))
        .isInstanceOf(com.openat.common.exception.BusinessException.class);
  }

  @Test
  void should_reject_stock_rollback_retry_for_refund_failed_order() {
    Order order = failedOrder(OrderFailCode.PG_ERROR);
    ReflectionTestUtils.setField(order, "status", OrderStatus.REFUND_FAILED);
    stubCompensating(order);

    assertThatThrownBy(() -> service.stockRollbackRetryTarget(order.getId()))
        .isInstanceOf(com.openat.common.exception.BusinessException.class);
    assertThatThrownBy(() -> service.completeStockRollbackRetry(order.getId()))
        .isInstanceOf(com.openat.common.exception.BusinessException.class);
  }

  @Test
  void should_reject_refund_retry_for_normal_cancel_requested_order() {
    Order order = completedCancellation();
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> service.prepareRefundRetry(order.getId()))
        .isInstanceOf(com.openat.common.exception.BusinessException.class);
  }

  @Test
  void should_allow_refund_retry_after_refund_request_failure() {
    Order order = completedCancellation();
    order.recordFailure(OrderFailCode.REFUND_REQUEST_FAILED, "timeout");
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    when(orderHistoryRepository.countByOrderIdAndReasonCode(
            order.getId(), "REFUND_RETRY_TRIGGERED"))
        .thenReturn(0L);

    assertThat(service.prepareRefundRetry(order.getId())).isEqualTo(1);

    verify(orderSagaRecorder).recordCompensating(order.getId());
  }

  private void stubCompensating(Order order) {
    OrderSagaState sagaState =
        OrderSagaState.create().orderId(order.getId()).sagaId(order.getId().toString()).build();
    sagaState.enterCompensating();
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    when(orderSagaStateRepository.findByOrderId(order.getId())).thenReturn(Optional.of(sagaState));
  }

  private Order failedOrder(OrderFailCode failCode) {
    Order order =
        Order.create()
            .orderNumber("ORD-1")
            .memberId(UUID.randomUUID())
            .dropId(UUID.randomUUID())
            .productId(UUID.randomUUID())
            .sellerId(UUID.randomUUID())
            .productName("상품")
            .quantity(1)
            .unitPrice(10_000L)
            .idempotencyKey("idem-" + failCode)
            .now(Instant.now())
            .build();
    ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
    order.fail(failCode, "failure", Instant.now());
    return order;
  }

  private Order completedCancellation() {
    Order order = failedOrder(OrderFailCode.PAYMENT_EXPIRED);
    ReflectionTestUtils.setField(order, "status", OrderStatus.PAYMENT_PENDING);
    order.clearFailure();
    order.complete(UUID.randomUUID(), Instant.now());
    order.requestRefund(Instant.now());
    return order;
  }
}
