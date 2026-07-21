package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
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
class OrderCancellationTransitionServiceTest {

  @Mock OrderRepository orderRepository;
  @Mock OrderHistoryRecorder orderHistoryRecorder;
  @Mock OrderSagaRecorder orderSagaRecorder;
  @InjectMocks OrderCancellationTransitionService service;

  @Test
  void should_not_record_compensating_when_cancel_transition_fails() {
    UUID memberId = UUID.randomUUID();
    Order order = order(memberId);
    order.complete(UUID.randomUUID(), Instant.now());
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    assertThatThrownBy(() -> service.cancelPaymentPending(memberId, order.getId(), "취소"))
        .isInstanceOf(BusinessException.class);

    verify(orderHistoryRecorder, never())
        .record(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verify(orderSagaRecorder, never()).recordCompensating(order.getId());
  }

  @Test
  void should_record_compensating_after_refund_transition_succeeds() {
    UUID memberId = UUID.randomUUID();
    Order order = order(memberId);
    order.complete(UUID.randomUUID(), Instant.now());
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    service.requestRefund(memberId, order.getId(), "refund-request-", true);

    assertThat(order.getFailCode()).isEqualTo(OrderFailCode.REFUND_REQUEST_FAILED);
    assertThat(order.getFailMessage()).isEqualTo("환불 요청 접수 미확인");
    verify(orderHistoryRecorder)
        .record(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    verify(orderSagaRecorder).recordCompensating(order.getId());
  }

  @Test
  void should_not_mark_confirmed_cancel_refund_transition() {
    UUID memberId = UUID.randomUUID();
    Order order = order(memberId);
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    service.requestRefund(memberId, order.getId(), "cancel-refund-accepted-", false);

    assertThat(order.getFailCode()).isNull();
  }

  @Test
  void should_clear_unconfirmed_refund_marker() {
    UUID memberId = UUID.randomUUID();
    Order order = order(memberId);
    order.recordFailure(OrderFailCode.REFUND_REQUEST_FAILED, "환불 요청 접수 미확인");
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    service.clearRefundRequestFailure(order.getId());

    assertThat(order.getFailCode()).isNull();
    assertThat(order.getFailMessage()).isNull();
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
            .quantity(1)
            .unitPrice(10_000L)
            .idempotencyKey("idem")
            .now(Instant.now())
            .build();
    ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
    return order;
  }
}
