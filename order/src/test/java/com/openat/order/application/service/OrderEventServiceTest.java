package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.PaymentCompletedCommand;
import com.openat.order.application.dto.PaymentFailedCommand;
import com.openat.order.application.dto.RefundCompletedCommand;
import com.openat.order.application.dto.RefundFailedCommand;
import com.openat.order.application.event.RefundStockRestoreRequested;
import com.openat.order.application.event.StockAdjustment;
import com.openat.order.application.event.StockAdjustmentReason;
import com.openat.order.application.port.OrderCompletedOutboxPort;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class OrderEventServiceTest {

  @Mock private OrderRepository orderRepository;

  @Mock private OrderHistoryRecorder orderHistoryRecorder;

  @Mock private OrderCompletedOutboxPort orderCompletedOutboxPort;

  @Mock private OrderSagaRecorder orderSagaRecorder;

  @Mock private ApplicationEventPublisher applicationEventPublisher;

  @InjectMocks private OrderEventService orderEventService;

  @Test
  @DisplayName("결제 성공 이벤트로 주문이 결제 완료 상태가 된다")
  void paymentComplete_changesOrderToCompleted() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    UUID orderId = order.getId();
    UUID paymentId = UUID.randomUUID();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

    PaymentCompletedCommand command = new PaymentCompletedCommand(orderId, paymentId, 10_000L);

    withTransactionSynchronization(() -> orderEventService.handlePaymentCompleted(command));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(order.getPaymentId()).isEqualTo(paymentId);
    ArgumentCaptor<String> sourceEventKey = ArgumentCaptor.forClass(String.class);
    verify(orderHistoryRecorder).record(any(), any(), any(), any(), sourceEventKey.capture());
    assertThat(sourceEventKey.getValue()).hasSizeLessThanOrEqualTo(100);
    verify(orderSagaRecorder).recordCompleted(orderId);
    verify(orderCompletedOutboxPort).save(order);
    ArgumentCaptor<StockAdjustment> adjustment = ArgumentCaptor.forClass(StockAdjustment.class);
    verify(applicationEventPublisher).publishEvent(adjustment.capture());
    assertThat(adjustment.getValue().eventId()).isNotNull();
    assertThat(adjustment.getValue().dropId()).isEqualTo(order.getDropId());
    assertThat(adjustment.getValue().count()).isEqualTo(order.getQuantity());
    assertThat(adjustment.getValue().reason()).isEqualTo(StockAdjustmentReason.COMPLETED);
  }

  @Test
  @DisplayName("이미 COMPLETED 상태면 결제 성공 이벤트는 중복 처리하지 않는다")
  void paymentComplete_whenAlreadyCompleted_noHistory() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    UUID paymentId = UUID.randomUUID();
    order.complete(paymentId, Instant.parse("2026-06-26T00:00:01Z"));
    UUID orderId = order.getId();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

    PaymentCompletedCommand command =
        new PaymentCompletedCommand(orderId, UUID.randomUUID(), 10_000L);

    orderEventService.handlePaymentCompleted(command);

    verify(orderHistoryRecorder, never()).record(any(), any(), any(), any(), any());
    verify(orderSagaRecorder, never()).recordCompleted(any());
    verify(orderCompletedOutboxPort, never()).save(any());
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  @Test
  void should_reconcile_payment_completed_when_lookup_amount_matches() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    UUID paymentId = UUID.randomUUID();
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    orderEventService.reconcilePaymentCompleted(order.getId(), paymentId, 10_000L);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    assertThat(order.getPaymentId()).isEqualTo(paymentId);
  }

  @Test
  void should_reject_payment_reconciliation_when_lookup_amount_mismatches() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    BusinessException exception =
        assertThrows(
            BusinessException.class,
            () ->
                orderEventService.reconcilePaymentCompleted(
                    order.getId(), UUID.randomUUID(), 9_999L));

    assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_INPUT);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
  }

  @Test
  @DisplayName("결제 실패 이벤트는 주문을 닫지 않고 시도 실패 이력만 남긴다")
  void paymentFailed_recordsAttemptFailureWithoutClosingOrder() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    UUID orderId = order.getId();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

    PaymentFailedCommand command =
        new PaymentFailedCommand(orderId, UUID.randomUUID(), "PG_TIMEOUT");

    orderEventService.handlePaymentFailed(command);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    assertThat(order.getFailCode()).isNull();
    ArgumentCaptor<Order> recordedOrder = ArgumentCaptor.forClass(Order.class);
    ArgumentCaptor<OrderStatus> previousStatus = ArgumentCaptor.forClass(OrderStatus.class);
    ArgumentCaptor<String> reasonCode = ArgumentCaptor.forClass(String.class);
    verify(orderHistoryRecorder)
        .record(
            recordedOrder.capture(), previousStatus.capture(), reasonCode.capture(), any(), any());
    assertThat(previousStatus.getValue()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    assertThat(recordedOrder.getValue().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    assertThat(reasonCode.getValue()).isEqualTo("PAYMENT_ATTEMPT_FAILED");
  }

  @Test
  @DisplayName("환불 완료 이벤트는 주문을 환불 완료로 반영한다")
  void refundCompleted_changesToRefunded() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
    order.requestRefund(Instant.parse("2026-06-26T00:00:01Z"));
    UUID orderId = order.getId();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

    RefundCompletedCommand command =
        new RefundCompletedCommand(orderId, UUID.randomUUID(), 10_000L, UUID.randomUUID());

    orderEventService.handleRefundCompleted(command);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    verify(orderHistoryRecorder).record(any(), any(), any(), any(), any());
    verify(applicationEventPublisher)
        .publishEvent(
            org.mockito.ArgumentMatchers.<Object>argThat(
                event ->
                    event instanceof StockAdjustment adjustment
                        && adjustment.eventId() != null
                        && adjustment.dropId().equals(order.getDropId())
                        && adjustment.count() == order.getQuantity()
                        && adjustment.reason() == StockAdjustmentReason.REFUNDED));
    verify(applicationEventPublisher)
        .publishEvent(
            org.mockito.ArgumentMatchers.<Object>argThat(
                event ->
                    event instanceof RefundStockRestoreRequested restore
                        && java.util.Objects.equals(restore.orderId(), orderId)
                        && restore.dropId().equals(order.getDropId())
                        && restore.memberId().equals(order.getMemberId())
                        && restore.quantity() == order.getQuantity()));
  }

  @Test
  @DisplayName("환불 실패 이벤트는 주문을 환불 실패 상태로 반영한다")
  void refundFailed_changesToRefundFailed() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
    order.requestRefund(Instant.parse("2026-06-26T00:00:02Z"));
    UUID orderId = order.getId();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

    RefundFailedCommand command =
        new RefundFailedCommand(orderId, UUID.randomUUID(), UUID.randomUUID(), "PG_REFUND_FAILED");

    orderEventService.handleRefundFailed(command);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUND_FAILED);
    assertThat(order.getFailCode()).isEqualTo(OrderFailCode.PG_ERROR);
    verify(orderHistoryRecorder).record(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("결제 성공 이벤트 금액이 주문 금액과 다르면 주문을 완료하지 않는다")
  void paymentComplete_whenAmountMismatch_throwInvalidInput() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    UUID orderId = order.getId();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

    PaymentCompletedCommand command =
        new PaymentCompletedCommand(orderId, UUID.randomUUID(), 9_999L);

    BusinessException ex =
        assertThrows(
            BusinessException.class, () -> orderEventService.handlePaymentCompleted(command));

    assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_INPUT);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    verify(orderHistoryRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("환불 완료 이벤트 금액이 주문 금액과 다르면 환불 완료로 전이하지 않는다")
  void refundCompleted_whenAmountMismatch_throwInvalidInput() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
    order.requestRefund(Instant.parse("2026-06-26T00:00:02Z"));
    UUID orderId = order.getId();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

    RefundCompletedCommand command =
        new RefundCompletedCommand(orderId, UUID.randomUUID(), 10_001L, UUID.randomUUID());

    BusinessException ex =
        assertThrows(
            BusinessException.class, () -> orderEventService.handleRefundCompleted(command));

    assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_INPUT);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
    verify(orderHistoryRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("환불 진행 중 주문의 부분환불 이벤트는 거부하고 보상을 시작하지 않는다")
  void refundCompleted_whenPendingPartialRefund_rejectsWithoutCompensation() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
    order.requestRefund(Instant.parse("2026-06-26T00:00:02Z"));
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    BusinessException ex =
        assertThrows(
            BusinessException.class,
            () ->
                orderEventService.handleRefundCompleted(
                    new RefundCompletedCommand(
                        order.getId(), UUID.randomUUID(), 4_000L, UUID.randomUUID())));

    assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_INPUT);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
    verify(orderHistoryRecorder, never()).record(any(), any(), any(), any(), any());
    verify(orderSagaRecorder, never()).recordCompensating(any());
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("결제 완료 주문의 직행 부분환불 이벤트는 거부한다")
  void refundCompleted_whenDirectPartialRefund_rejects() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    BusinessException ex =
        assertThrows(
            BusinessException.class,
            () ->
                orderEventService.handleRefundCompleted(
                    new RefundCompletedCommand(
                        order.getId(), UUID.randomUUID(), 4_000L, UUID.randomUUID())));

    assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_INPUT);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    verify(orderHistoryRecorder, never()).record(any(), any(), any(), any(), any());
    verify(orderSagaRecorder, never()).recordCompensating(any());
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("이미 환불 완료된 주문도 부분환불 이벤트는 거부한다")
  void refundCompleted_whenAlreadyRefundedPartialRefund_rejects() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
    order.requestRefund(Instant.parse("2026-06-26T00:00:02Z"));
    order.refund(Instant.parse("2026-06-26T00:00:03Z"));
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    BusinessException ex =
        assertThrows(
            BusinessException.class,
            () ->
                orderEventService.handleRefundCompleted(
                    new RefundCompletedCommand(
                        order.getId(), UUID.randomUUID(), 4_000L, UUID.randomUUID())));

    assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_INPUT);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    verify(orderHistoryRecorder, never()).record(any(), any(), any(), any(), any());
    verify(orderSagaRecorder, never()).recordCompensating(any());
    verify(applicationEventPublisher, never()).publishEvent(any());
  }

  @Test
  @DisplayName("결제 완료 주문의 직행 전액환불 이벤트는 환불 완료 처리한다")
  void refundCompleted_whenDirectFullRefund_changesToRefunded() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    orderEventService.handleRefundCompleted(
        new RefundCompletedCommand(order.getId(), UUID.randomUUID(), 10_000L, UUID.randomUUID()));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    verify(orderSagaRecorder).recordCompensating(order.getId());
    verify(applicationEventPublisher).publishEvent(any(RefundStockRestoreRequested.class));
  }

  @Test
  @DisplayName("이미 보상 완료된 주문은 환불 완료 시 재고복구를 다시 요청하지 않는다")
  void refundCompleted_whenCompensationAlreadyCompleted_skipsStockRestore() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    when(orderSagaRecorder.isCompensationCompleted(order.getId())).thenReturn(true);

    orderEventService.handleRefundCompleted(
        new RefundCompletedCommand(order.getId(), UUID.randomUUID(), 10_000L, UUID.randomUUID()));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    verify(orderSagaRecorder, never()).recordCompensating(any());
    verify(applicationEventPublisher, never()).publishEvent(any(RefundStockRestoreRequested.class));
  }

  @Test
  @DisplayName("결제 완료 상태의 주문에 결제 실패 이벤트가 오면 무시한다")
  void paymentFailed_whenAlreadyCompleted_ignoreStaleFailure() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
    UUID orderId = order.getId();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

    PaymentFailedCommand command =
        new PaymentFailedCommand(orderId, UUID.randomUUID(), "PG_TIMEOUT");

    orderEventService.handlePaymentFailed(command);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
    verify(orderHistoryRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("이미 REFUND_FAILED 상태면 환불 실패 이벤트는 중복 처리하지 않는다")
  void refundFailed_whenAlreadyRefundFailed_noHistory() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
    order.requestRefund(Instant.parse("2026-06-26T00:00:02Z"));
    order.failRefund("PG_REFUND_FAILED");
    UUID orderId = order.getId();

    when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

    RefundFailedCommand command =
        new RefundFailedCommand(orderId, UUID.randomUUID(), UUID.randomUUID(), "PG_REFUND_FAILED");

    orderEventService.handleRefundFailed(command);

    verify(orderHistoryRecorder, never()).record(any(), any(), any(), any(), any());
  }

  @Test
  void should_complete_retried_refund_and_publish_paired_stock_adjustment() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
    order.requestRefund(Instant.parse("2026-06-26T00:00:02Z"));
    order.failRefund("PG_REFUND_FAILED");
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    orderEventService.handleRefundCompleted(
        new RefundCompletedCommand(order.getId(), UUID.randomUUID(), 10_000L, UUID.randomUUID()));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    assertThat(order.getFailCode()).isNull();
    verify(applicationEventPublisher)
        .publishEvent(
            org.mockito.ArgumentMatchers.<Object>argThat(StockAdjustment.class::isInstance));
    verify(applicationEventPublisher).publishEvent(any(RefundStockRestoreRequested.class));
  }

  @Test
  void should_converge_cancelled_to_refunded_when_refund_completed_arrives() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.cancelPending(Instant.parse("2026-06-26T00:01:00Z"));
    UUID orderId = order.getId();
    when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

    orderEventService.handleRefundCompleted(
        new RefundCompletedCommand(orderId, UUID.randomUUID(), 10_000L, UUID.randomUUID()));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    verify(orderHistoryRecorder).record(any(), any(), any(), any(), any());
    verify(orderSagaRecorder, never()).recordCompensationCompleted(any());
    verify(applicationEventPublisher, never())
        .publishEvent(
            org.mockito.ArgumentMatchers.<Object>argThat(StockAdjustment.class::isInstance));
    verify(applicationEventPublisher).publishEvent(any(RefundStockRestoreRequested.class));
  }

  @Test
  void should_not_publish_unpaired_stock_adjustment_for_pending_refund() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.requestRefund(Instant.parse("2026-06-26T00:00:01Z"));
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    orderEventService.handleRefundCompleted(
        new RefundCompletedCommand(order.getId(), UUID.randomUUID(), 10_000L, UUID.randomUUID()));

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    verify(applicationEventPublisher, never())
        .publishEvent(
            org.mockito.ArgumentMatchers.<Object>argThat(StockAdjustment.class::isInstance));
    verify(applicationEventPublisher).publishEvent(any(RefundStockRestoreRequested.class));
  }

  @Test
  void should_reject_refund_failed_when_order_cancelled() {
    Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
    order.cancelPending(Instant.parse("2026-06-26T00:01:00Z"));
    UUID orderId = order.getId();
    when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

    assertThatThrownBy(
            () ->
                orderEventService.handleRefundFailed(
                    new RefundFailedCommand(
                        orderId, UUID.randomUUID(), UUID.randomUUID(), "failed")))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("주문이 없으면 주문 조회 이벤트는 예외가 발생한다")
  void event_whenOrderNotFound_throwNotFound() {
    UUID orderId = UUID.randomUUID();
    when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

    PaymentCompletedCommand command =
        new PaymentCompletedCommand(orderId, UUID.randomUUID(), 10_000L);

    var ex =
        assertThrows(
            BusinessException.class, () -> orderEventService.handlePaymentCompleted(command));

    assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.NOT_FOUND);
  }

  private Order createOrder(Instant now) {
    return Order.create()
        .orderNumber("ORD-20260626-0001")
        .memberId(UUID.randomUUID())
        .dropId(UUID.randomUUID())
        .productId(UUID.randomUUID())
        .sellerId(UUID.randomUUID())
        .quantity(2)
        .unitPrice(5_000L)
        .idempotencyKey("idem-001")
        .now(now)
        .build();
  }

  private void withTransactionSynchronization(Runnable action) {
    TransactionSynchronizationManager.initSynchronization();
    try {
      action.run();
      for (TransactionSynchronization synchronization :
          TransactionSynchronizationManager.getSynchronizations()) {
        synchronization.afterCommit();
      }
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }
}
