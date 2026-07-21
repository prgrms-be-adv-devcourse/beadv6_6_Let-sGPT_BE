package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.CreateOrderCommand;
import com.openat.order.application.dto.OrderSnapshotInfo;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.repository.OrderHistoryRepository;
import com.openat.order.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class PendingOrderCreatorTest {

  @Mock private OrderRepository orderRepository;

  @Mock private OrderHistoryRepository orderHistoryRepository;

  @Mock private OrderSagaRecorder orderSagaRecorder;

  @InjectMocks private PendingOrderCreator pendingOrderCreator;

  @Test
  @DisplayName("주문 생성은 주문 저장과 같은 트랜잭션에서 사가 ORDER_CREATED 기록을 요청한다")
  void create_recordsOrderCreatedSagaWithSavedOrder() {
    // given
    UUID memberId = UUID.randomUUID();
    CreateOrderCommand command = new CreateOrderCommand(UUID.randomUUID(), 2, "idem-001", "테스트 상품");
    OrderSnapshotInfo snapshot =
        new OrderSnapshotInfo(UUID.randomUUID(), UUID.randomUUID(), 10_000L, "스냅샷 상품");
    Instant now = Instant.parse("2026-06-26T00:00:00Z");
    UUID generatedId = UUID.randomUUID();

    when(orderRepository.saveAndFlush(any(Order.class)))
        .thenAnswer(
            invocation -> {
              Order order = invocation.getArgument(0);
              ReflectionTestUtils.setField(order, "id", generatedId);
              return order;
            });

    // when
    Order result = pendingOrderCreator.create(memberId, command, snapshot, now);

    // then
    assertThat(result.getId()).isEqualTo(generatedId);
    ArgumentCaptor<Order> savedOrderCaptor = ArgumentCaptor.forClass(Order.class);
    verify(orderSagaRecorder).recordOrderCreated(savedOrderCaptor.capture());
    assertThat(savedOrderCaptor.getValue().getId()).isEqualTo(generatedId);
    verify(orderHistoryRepository).save(any());
  }

  @Test
  @DisplayName("스냅샷 상품명이 있으면 요청 표시명 대신 스냅샷 값을 저장한다")
  void create_prefersSnapshotProductName() {
    UUID memberId = UUID.randomUUID();
    CreateOrderCommand command = new CreateOrderCommand(UUID.randomUUID(), 1, "idem-002", "요청 표시명");
    OrderSnapshotInfo snapshot =
        new OrderSnapshotInfo(UUID.randomUUID(), UUID.randomUUID(), 10_000L, "스냅샷 상품");
    when(orderRepository.saveAndFlush(any(Order.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Order result =
        pendingOrderCreator.create(
            memberId, command, snapshot, Instant.parse("2026-06-26T00:00:00Z"));

    assertThat(result.getProductName()).isEqualTo("스냅샷 상품");
  }

  @Test
  @DisplayName("스냅샷 상품명이 없으면 요청 표시명으로 저장한다")
  void create_fallsBackToRequestOrderName() {
    UUID memberId = UUID.randomUUID();
    CreateOrderCommand command = new CreateOrderCommand(UUID.randomUUID(), 1, "idem-003", "요청 표시명");
    OrderSnapshotInfo snapshot =
        new OrderSnapshotInfo(UUID.randomUUID(), UUID.randomUUID(), 10_000L, null);
    when(orderRepository.saveAndFlush(any(Order.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    Order result =
        pendingOrderCreator.create(
            memberId, command, snapshot, Instant.parse("2026-06-26T00:00:00Z"));

    assertThat(result.getProductName()).isEqualTo("요청 표시명");
  }

  @Test
  @DisplayName("스냅샷과 요청 모두 표시명이 없으면 잘못된 입력으로 거부한다")
  void create_rejectsWhenNoProductNameAvailable() {
    UUID memberId = UUID.randomUUID();
    CreateOrderCommand command = new CreateOrderCommand(UUID.randomUUID(), 1, "idem-004", " ");
    OrderSnapshotInfo snapshot =
        new OrderSnapshotInfo(UUID.randomUUID(), UUID.randomUUID(), 10_000L, null);

    BusinessException ex =
        assertThrows(
            BusinessException.class,
            () ->
                pendingOrderCreator.create(
                    memberId, command, snapshot, Instant.parse("2026-06-26T00:00:00Z")));

    assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_INPUT);
  }
}
