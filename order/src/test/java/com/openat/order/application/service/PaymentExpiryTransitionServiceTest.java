package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
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
class PaymentExpiryTransitionServiceTest {

  @Mock OrderRepository orderRepository;
  @Mock OrderHistoryRecorder orderHistoryRecorder;
  @Mock OrderSagaRecorder orderSagaRecorder;
  @InjectMocks PaymentExpiryTransitionService service;

  @Test
  void should_close_order_on_third_persisted_lookup_failure() {
    Order order = order();
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    Instant first = Instant.parse("2026-07-19T00:00:00Z");

    assertThat(service.recordLookupFailure(order.getId(), first)).isEmpty();
    assertThat(service.recordLookupFailure(order.getId(), first.plusSeconds(31))).isEmpty();
    assertThat(service.recordLookupFailure(order.getId(), first.plusSeconds(62))).isPresent();

    assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    assertThat(order.getFailCode()).isEqualTo(OrderFailCode.PAYMENT_NO_RESPONSE);
    verify(orderHistoryRecorder)
        .record(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.eq("PAYMENT_NO_RESPONSE"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verify(orderSagaRecorder).recordCompensating(order.getId());
  }

  @Test
  void should_not_inflate_failure_count_within_same_scheduler_window() {
    Order order = order();
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    Instant first = Instant.parse("2026-07-19T00:00:00Z");

    service.recordLookupFailure(order.getId(), first);
    service.recordLookupFailure(order.getId(), first.plusSeconds(10));
    service.recordLookupFailure(order.getId(), first.plusSeconds(20));

    assertThat(order.getPaymentStatusCheckFailureCount()).isEqualTo(1);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
    verify(orderSagaRecorder, never()).recordCompensating(order.getId());
  }

  @Test
  void should_defer_next_pending_payment_lookup() {
    Order order = order();
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    Instant nextCheckAt = Instant.now().plusSeconds(300);

    service.deferPendingLookup(order.getId(), nextCheckAt);

    assertThat(order.getNextPaymentStatusCheckAt()).isEqualTo(nextCheckAt);
    assertThat(order.getPaymentStatusCheckFailureCount()).isZero();
  }

  @Test
  void should_defer_amount_mismatch_and_keep_order_pending() {
    Order order = order();
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    Instant nextCheckAt = Instant.now().plusSeconds(300);

    long amount = service.deferPaymentMismatch(order.getId(), nextCheckAt);

    assertThat(amount).isEqualTo(order.getTotalPrice());
    assertThat(order.getNextPaymentStatusCheckAt()).isEqualTo(nextCheckAt);
    assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
  }

  private Order order() {
    Order order =
        Order.create()
            .orderNumber("ORD-1")
            .memberId(UUID.randomUUID())
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
