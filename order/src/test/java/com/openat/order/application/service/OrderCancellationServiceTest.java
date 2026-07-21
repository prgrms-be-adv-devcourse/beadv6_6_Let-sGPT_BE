package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.OrderCancelInfo;
import com.openat.order.application.dto.PaymentRefundResult;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.port.PaymentPendingException;
import com.openat.order.application.port.PaymentRefundPort;
import com.openat.order.application.port.PaymentRefundPortException;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderCancellationServiceTest {

  @Mock OrderRepository orderRepository;
  @Mock OrderCancellationTransitionService transitionService;
  @Mock PaymentRefundPort paymentRefundPort;
  @Mock ProductIntegrationPort productIntegrationPort;
  @Mock OrderSagaRecorder orderSagaRecorder;
  @Mock OrderCompensationFailureRecorder compensationFailureRecorder;
  @InjectMocks OrderCancellationService service;

  @Test
  void should_cancel_and_restore_stock_when_payment_not_completed() {
    UUID memberId = UUID.randomUUID();
    Order order = order(memberId);
    stubOwned(order);
    when(paymentRefundPort.requestRefund(order.getId(), "refund-order-" + order.getId()))
        .thenReturn(PaymentRefundResult.PAYMENT_NOT_COMPLETED);
    when(transitionService.cancelPaymentPending(memberId, order.getId(), "결제 전 주문 취소"))
        .thenReturn(new OrderCancelInfo(order.getId(), OrderStatus.CANCELLED));

    OrderCancelInfo result = service.cancel(memberId, order.getId());

    assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
    verify(orderSagaRecorder).recordCompensationCompleted(order.getId());
    ArgumentCaptor<StockRestoreCommand> command =
        ArgumentCaptor.forClass(StockRestoreCommand.class);
    verify(productIntegrationPort).restoreStock(any(), command.capture());
    assertThat(command.getValue().orderId()).isEqualTo(order.getId());
  }

  @Test
  void should_wait_for_refund_event_when_refund_accepted() {
    UUID memberId = UUID.randomUUID();
    Order order = order(memberId);
    stubOwned(order);
    when(paymentRefundPort.requestRefund(any(), any()))
        .thenReturn(PaymentRefundResult.REFUND_ACCEPTED);
    when(transitionService.requestRefund(memberId, order.getId(), "cancel-refund-accepted-", false))
        .thenReturn(new OrderCancelInfo(order.getId(), OrderStatus.CANCEL_REQUESTED));

    OrderCancelInfo result = service.cancel(memberId, order.getId());

    assertThat(result.status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
    verify(productIntegrationPort, never()).restoreStock(any(), any());
    verify(orderSagaRecorder, never()).recordCompensationCompleted(any());
  }

  @Test
  void should_optimistically_cancel_when_payment_call_fails() {
    UUID memberId = UUID.randomUUID();
    Order order = order(memberId);
    stubOwned(order);
    when(paymentRefundPort.requestRefund(any(), any()))
        .thenThrow(new PaymentRefundPortException("timeout", new RuntimeException()));
    when(transitionService.cancelPaymentPending(memberId, order.getId(), "환불 API 무응답으로 결제 전 낙관 확정"))
        .thenReturn(new OrderCancelInfo(order.getId(), OrderStatus.CANCELLED));

    OrderCancelInfo result = service.cancel(memberId, order.getId());

    assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
    verify(productIntegrationPort).restoreStock(any(), any());
  }

  @Test
  void should_reject_cancel_without_state_or_stock_change_when_payment_is_pending() {
    UUID memberId = UUID.randomUUID();
    Order order = order(memberId);
    stubOwned(order);
    when(paymentRefundPort.requestRefund(any(), any()))
        .thenThrow(new PaymentPendingException("pending", new RuntimeException()));

    assertThatThrownBy(() -> service.cancel(memberId, order.getId()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode())
                    .isEqualTo(OrderErrorCode.PAYMENT_IN_PROGRESS));
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    verify(transitionService, never()).cancelPaymentPending(any(), any(), any());
    verify(transitionService, never()).requestRefund(any(), any(), any(), any(Boolean.class));
    verify(productIntegrationPort, never()).restoreStock(any(), any());
    verify(orderSagaRecorder, never()).recordCompensationCompleted(any());
  }

  @Test
  void should_keep_cancelled_when_stock_rollback_fails() {
    UUID memberId = UUID.randomUUID();
    Order order = order(memberId);
    stubOwned(order);
    when(paymentRefundPort.requestRefund(any(), any()))
        .thenReturn(PaymentRefundResult.PAYMENT_NOT_COMPLETED);
    when(transitionService.cancelPaymentPending(any(), any(), any()))
        .thenReturn(new OrderCancelInfo(order.getId(), OrderStatus.CANCELLED));
    org.mockito.Mockito.doThrow(
            new ProductPortException(OrderFailCode.STOCK_ROLLBACK_FAILED, "rollback failed"))
        .when(productIntegrationPort)
        .restoreStock(any(), any());

    OrderCancelInfo result = service.cancel(memberId, order.getId());

    assertThat(result.status()).isEqualTo(OrderStatus.CANCELLED);
    verify(compensationFailureRecorder)
        .recordStockRollbackFailure(order.getId(), "rollback failed");
    verify(orderSagaRecorder, never()).recordCompensationCompleted(any());
  }

  @Test
  void should_reject_cancel_when_order_completed() {
    UUID memberId = UUID.randomUUID();
    Order order = order(memberId);
    order.complete(UUID.randomUUID(), Instant.now());
    stubOwned(order);

    assertThatThrownBy(() -> service.cancel(memberId, order.getId()))
        .isInstanceOfSatisfying(
            BusinessException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.ALREADY_COMPLETED));
    verify(paymentRefundPort, never()).requestRefund(any(), any());
  }

  @Test
  void should_return_cancel_requested_when_refund_call_fails_after_transition() {
    UUID memberId = UUID.randomUUID();
    Order order = order(memberId);
    order.complete(UUID.randomUUID(), Instant.now());
    stubOwned(order);
    when(transitionService.requestRefund(memberId, order.getId(), "refund-request-", true))
        .thenReturn(new OrderCancelInfo(order.getId(), OrderStatus.CANCEL_REQUESTED));
    when(paymentRefundPort.requestRefund(any(), any()))
        .thenThrow(new PaymentRefundPortException("timeout", new RuntimeException()));

    OrderCancelInfo result = service.requestRefund(memberId, order.getId());

    assertThat(result.status()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
    verify(compensationFailureRecorder).recordRefundRequestFailure(order.getId(), "timeout");
  }

  @Test
  void should_clear_unconfirmed_marker_when_refund_is_accepted() {
    UUID memberId = UUID.randomUUID();
    Order order = order(memberId);
    order.complete(UUID.randomUUID(), Instant.now());
    stubOwned(order);
    when(transitionService.requestRefund(memberId, order.getId(), "refund-request-", true))
        .thenReturn(new OrderCancelInfo(order.getId(), OrderStatus.CANCEL_REQUESTED));
    when(paymentRefundPort.requestRefund(any(), any()))
        .thenReturn(PaymentRefundResult.REFUND_ACCEPTED);

    service.requestRefund(memberId, order.getId());

    verify(transitionService).clearRefundRequestFailure(order.getId());
    verify(compensationFailureRecorder, never()).recordRefundRequestFailure(any(), any());
  }

  private void stubOwned(Order order) {
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
  }

  private Order order(UUID memberId) {
    Order order =
        Order.create()
            .orderNumber("ORD-1")
            .memberId(memberId)
            .dropId(UUID.randomUUID())
            .productId(UUID.randomUUID())
            .sellerId(UUID.randomUUID())
            .productName("상품")
            .quantity(2)
            .unitPrice(10_000L)
            .idempotencyKey("idem")
            .now(Instant.now())
            .build();
    ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
    return order;
  }
}
