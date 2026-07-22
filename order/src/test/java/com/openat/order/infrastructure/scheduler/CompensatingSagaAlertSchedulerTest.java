package com.openat.order.infrastructure.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.application.service.OrderCompensationService;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderSagaState;
import com.openat.order.domain.repository.OrderRepository;
import com.openat.order.domain.repository.OrderSagaStateRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
class CompensatingSagaAlertSchedulerTest {

  @Mock OrderSagaStateRepository orderSagaStateRepository;
  @Mock OrderRepository orderRepository;
  @Mock OrderCompensationService orderCompensationService;
  @InjectMocks CompensatingSagaAlertScheduler scheduler;

  @Test
  void should_retry_refunded_compensation_after_two_minutes() {
    Order order = refundedOrder(Instant.now().minus(Duration.ofMinutes(3)));
    OrderSagaState saga = compensatingSaga(order.getId());
    when(orderSagaStateRepository.findCompensating()).thenReturn(List.of(saga));
    when(orderSagaStateRepository.findCompensatingBefore(any())).thenReturn(List.of());
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    scheduler.processCompensations();

    verify(orderCompensationService).retryStockRollback(order.getId());
  }

  @Test
  void should_not_retry_refunded_compensation_during_grace_period() {
    Order order = refundedOrder(Instant.now().minus(Duration.ofMinutes(1)));
    OrderSagaState saga = compensatingSaga(order.getId());
    when(orderSagaStateRepository.findCompensating()).thenReturn(List.of(saga));
    when(orderSagaStateRepository.findCompensatingBefore(any())).thenReturn(List.of());
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    scheduler.processCompensations();

    verify(orderCompensationService, never()).retryStockRollback(any());
  }

  @Test
  void should_retry_cancelled_compensation_after_two_minutes() {
    Order order = cancelledOrder(Instant.now().minus(Duration.ofMinutes(3)));
    OrderSagaState saga = compensatingSaga(order.getId());
    when(orderSagaStateRepository.findCompensating()).thenReturn(List.of(saga));
    when(orderSagaStateRepository.findCompensatingBefore(any())).thenReturn(List.of());
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    scheduler.processCompensations();

    verify(orderCompensationService).retryStockRollback(order.getId());
  }

  @Test
  void should_query_only_compensations_older_than_ten_minutes_for_alerts() {
    when(orderSagaStateRepository.findCompensating()).thenReturn(List.of());
    when(orderSagaStateRepository.findCompensatingBefore(any()))
        .thenReturn(List.of(compensatingSaga(UUID.randomUUID())));
    Instant before = Instant.now();

    scheduler.processCompensations();

    Instant after = Instant.now();
    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(orderSagaStateRepository).findCompensatingBefore(cutoff.capture());
    assertThat(cutoff.getValue())
        .isBetween(
            before.minus(CompensatingSagaAlertScheduler.ALERT_THRESHOLD),
            after.minus(CompensatingSagaAlertScheduler.ALERT_THRESHOLD));
    assertThat(CompensatingSagaAlertScheduler.ALERT_THRESHOLD).isEqualTo(Duration.ofMinutes(10));
  }

  private Order refundedOrder(Instant refundedAt) {
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
            .now(refundedAt.minusSeconds(60))
            .build();
    ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
    order.complete(UUID.randomUUID(), refundedAt.minusSeconds(30));
    order.refund(refundedAt);
    return order;
  }

  private Order cancelledOrder(Instant cancelledAt) {
    Order order = pendingOrder(cancelledAt.minusSeconds(60));
    order.cancelPending(cancelledAt);
    return order;
  }

  private Order pendingOrder(Instant now) {
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
            .now(now)
            .build();
    ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
    return order;
  }

  private OrderSagaState compensatingSaga(UUID orderId) {
    OrderSagaState state =
        OrderSagaState.create().orderId(orderId).sagaId(orderId.toString()).build();
    state.enterCompensating();
    return state;
  }
}
