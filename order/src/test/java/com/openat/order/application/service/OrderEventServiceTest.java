package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class OrderEventServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderHistoryRecorder orderHistoryRecorder;

    @Mock
    private OrderCompletedOutboxPort orderCompletedOutboxPort;

    @Mock
    private OrderSagaRecorder orderSagaRecorder;

    @InjectMocks
    private OrderEventService orderEventService;

    @Test
    @DisplayName("결제 성공 이벤트로 주문이 결제 완료 상태가 된다")
    void paymentComplete_changesOrderToCompleted() {
        // given
        Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
        UUID orderId = order.getId();
        UUID paymentId = UUID.randomUUID();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        PaymentCompletedCommand command = new PaymentCompletedCommand(orderId, paymentId, 10_000L);

        // when
        withTransactionSynchronization(() -> orderEventService.handlePaymentCompleted(command));

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getPaymentId()).isEqualTo(paymentId);
        ArgumentCaptor<String> sourceEventKey = ArgumentCaptor.forClass(String.class);
        verify(orderHistoryRecorder).record(any(), any(), any(), any(), sourceEventKey.capture());
        assertThat(sourceEventKey.getValue()).hasSizeLessThanOrEqualTo(100);
        verify(orderSagaRecorder).recordCompleted(orderId);
        verify(orderCompletedOutboxPort).save(order);
    }

    @Test
    @DisplayName("이미 COMPLETED 상태면 결제 성공 이벤트는 중복 처리하지 않는다")
    void paymentComplete_whenAlreadyCompleted_noHistory() {
        // given
        Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
        UUID paymentId = UUID.randomUUID();
        order.complete(paymentId, Instant.parse("2026-06-26T00:00:01Z"));
        UUID orderId = order.getId();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        PaymentCompletedCommand command = new PaymentCompletedCommand(orderId, UUID.randomUUID(), 10_000L);

        // when
        orderEventService.handlePaymentCompleted(command);

        // then
        verify(orderHistoryRecorder, never()).record(any(), any(), any(), any(), any());
        verify(orderSagaRecorder, never()).recordCompleted(any());
        verify(orderCompletedOutboxPort, never()).save(any());
    }

    @Test
    @DisplayName("결제 실패 이벤트는 주문을 닫지 않고 시도 실패 이력만 남긴다")
    void paymentFailed_recordsAttemptFailureWithoutClosingOrder() {
        // given
        Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
        UUID orderId = order.getId();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        PaymentFailedCommand command = new PaymentFailedCommand(orderId, UUID.randomUUID(), "PG_TIMEOUT");

        // when
        orderEventService.handlePaymentFailed(command);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(order.getFailCode()).isNull();
        ArgumentCaptor<Order> recordedOrder = ArgumentCaptor.forClass(Order.class);
        ArgumentCaptor<OrderStatus> previousStatus = ArgumentCaptor.forClass(OrderStatus.class);
        ArgumentCaptor<String> reasonCode = ArgumentCaptor.forClass(String.class);
        verify(orderHistoryRecorder).record(recordedOrder.capture(), previousStatus.capture(), reasonCode.capture(), any(), any());
        assertThat(previousStatus.getValue()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(recordedOrder.getValue().getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(reasonCode.getValue()).isEqualTo("PAYMENT_ATTEMPT_FAILED");
    }

    @Test
    @DisplayName("환불 완료 이벤트는 주문을 환불 완료로 반영한다")
    void refundCompleted_changesToRefunded() {
        // given
        Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
        order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
        order.requestRefund(Instant.parse("2026-06-26T00:00:01Z"));
        UUID orderId = order.getId();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        RefundCompletedCommand command = new RefundCompletedCommand(orderId, UUID.randomUUID(), 10_000L, UUID.randomUUID());

        // when
        orderEventService.handleRefundCompleted(command);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        verify(orderHistoryRecorder).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("환불 실패 이벤트는 주문을 환불 실패 상태로 반영한다")
    void refundFailed_changesToRefundFailed() {
        // given
        Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
        order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
        order.requestRefund(Instant.parse("2026-06-26T00:00:02Z"));
        UUID orderId = order.getId();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        RefundFailedCommand command = new RefundFailedCommand(
                orderId, UUID.randomUUID(), UUID.randomUUID(), "PG_REFUND_FAILED"
        );

        // when
        orderEventService.handleRefundFailed(command);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUND_FAILED);
        assertThat(order.getFailCode()).isEqualTo(OrderFailCode.PG_ERROR);
        verify(orderHistoryRecorder).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("결제 성공 이벤트 금액이 주문 금액과 다르면 주문을 완료하지 않는다")
    void paymentComplete_whenAmountMismatch_throwInvalidInput() {
        // given
        Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
        UUID orderId = order.getId();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        PaymentCompletedCommand command = new PaymentCompletedCommand(orderId, UUID.randomUUID(), 9_999L);

        // when
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderEventService.handlePaymentCompleted(command));

        // then
        assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_INPUT);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        verify(orderHistoryRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("환불 완료 이벤트 금액이 주문 금액과 다르면 환불 완료로 전이하지 않는다")
    void refundCompleted_whenAmountMismatch_throwInvalidInput() {
        // given
        Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
        order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
        order.requestRefund(Instant.parse("2026-06-26T00:00:02Z"));
        UUID orderId = order.getId();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        RefundCompletedCommand command = new RefundCompletedCommand(
                orderId, UUID.randomUUID(), 9_999L, UUID.randomUUID()
        );

        // when
        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderEventService.handleRefundCompleted(command));

        // then
        assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_INPUT);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        verify(orderHistoryRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("결제 완료 상태의 주문에 결제 실패 이벤트가 오면 무시한다")
    void paymentFailed_whenAlreadyCompleted_ignoreStaleFailure() {
        // given
        Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
        order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
        UUID orderId = order.getId();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        PaymentFailedCommand command = new PaymentFailedCommand(orderId, UUID.randomUUID(), "PG_TIMEOUT");

        // when
        orderEventService.handlePaymentFailed(command);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        verify(orderHistoryRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("이미 REFUND_FAILED 상태면 환불 실패 이벤트는 중복 처리하지 않는다")
    void refundFailed_whenAlreadyRefundFailed_noHistory() {
        // given
        Order order = createOrder(Instant.parse("2026-06-26T00:00:00Z"));
        order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
        order.requestRefund(Instant.parse("2026-06-26T00:00:02Z"));
        order.failRefund("PG_REFUND_FAILED");
        UUID orderId = order.getId();

        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));

        RefundFailedCommand command = new RefundFailedCommand(
                orderId, UUID.randomUUID(), UUID.randomUUID(), "PG_REFUND_FAILED"
        );

        // when
        orderEventService.handleRefundFailed(command);

        // then
        verify(orderHistoryRecorder, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("주문이 없으면 주문 조회 이벤트는 예외가 발생한다")
    void event_whenOrderNotFound_throwNotFound() {
        // given
        UUID orderId = UUID.randomUUID();
        when(orderRepository.findById(orderId)).thenReturn(Optional.empty());

        PaymentCompletedCommand command = new PaymentCompletedCommand(orderId, UUID.randomUUID(), 10_000L);

        // when
        var ex = assertThrows(BusinessException.class,
                () -> orderEventService.handlePaymentCompleted(command));

        // then
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
            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
