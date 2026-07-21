package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderCompensationFailureRecorderTest {

  @Mock OrderRepository orderRepository;
  @Mock OrderHistoryRecorder orderHistoryRecorder;
  @Mock OrderSagaRecorder orderSagaRecorder;
  @InjectMocks OrderCompensationFailureRecorder recorder;

  @Test
  void should_keep_refunded_status_and_enter_compensating_when_restore_fails() {
    Order order = refundedOrder();
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    recorder.recordStockRollbackFailure(order.getId(), "timeout");

    assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
    assertThat(order.getFailCode()).isEqualTo(OrderFailCode.STOCK_ROLLBACK_FAILED);
    verify(orderSagaRecorder).recordCompensating(order.getId());
    verify(orderHistoryRecorder)
        .record(
            order,
            OrderStatus.REFUNDED,
            "STOCK_ROLLBACK_FAILED",
            "timeout",
            "stock-rollback-failed-" + order.getId());
  }

  @Test
  void should_preserve_existing_failure_code_when_stock_rollback_fails() {
    Order order = refundedOrder();
    order.recordFailure(OrderFailCode.PAYMENT_EXPIRED, "expired");
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    recorder.recordStockRollbackFailure(order.getId(), "rollback timeout");

    assertThat(order.getFailCode()).isEqualTo(OrderFailCode.PAYMENT_EXPIRED);
    assertThat(order.getFailMessage()).isEqualTo("expired");
    verify(orderHistoryRecorder)
        .record(
            order,
            OrderStatus.REFUNDED,
            "STOCK_ROLLBACK_FAILED",
            "rollback timeout",
            "stock-rollback-failed-" + order.getId());
    verify(orderSagaRecorder).recordCompensating(order.getId());
  }

  private Order refundedOrder() {
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
            .idempotencyKey("idem")
            .now(Instant.now())
            .build();
    ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
    order.complete(UUID.randomUUID(), Instant.now());
    order.requestRefund(Instant.now());
    order.refund(Instant.now());
    return order;
  }
}
