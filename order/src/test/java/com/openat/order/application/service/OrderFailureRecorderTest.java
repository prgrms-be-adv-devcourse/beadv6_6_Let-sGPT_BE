package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderHistoryRepository;
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
class OrderFailureRecorderTest {

  @Mock OrderRepository orderRepository;
  @Mock OrderHistoryRepository orderHistoryRepository;
  @Mock OrderSagaRecorder orderSagaRecorder;
  @InjectMocks OrderFailureRecorder recorder;

  @Test
  void should_record_failed_and_compensating_together() {
    Order order = order();
    when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

    recorder.recordCreateFailure(
        order.getId(), OrderFailCode.PRODUCT_INTEGRATION_FAILED, "timeout", Instant.now(), true);

    assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    assertThat(order.getFailCode()).isEqualTo(OrderFailCode.PRODUCT_INTEGRATION_FAILED);
    verify(orderHistoryRepository).save(org.mockito.ArgumentMatchers.any());
    verify(orderSagaRecorder).recordCompensating(order.getId());
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
            .quantity(1)
            .unitPrice(10_000L)
            .idempotencyKey("idem")
            .now(Instant.now())
            .build();
    ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
    return order;
  }
}
