package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderSagaState;
import com.openat.order.domain.model.OrderSagaStep;
import com.openat.order.domain.repository.OrderSagaStateRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderSagaRecorderTest {

  @Mock private OrderSagaStateRepository orderSagaStateRepository;

  private OrderSagaRecorder orderSagaRecorder;

  @BeforeEach
  void setUp() {
    orderSagaRecorder = new OrderSagaRecorder(orderSagaStateRepository, new SimpleMeterRegistry());
  }

  @Test
  @DisplayName("주문 생성 시 사가 상태를 ORDER_CREATED로 생성하고 주문에 sagaId를 채운다")
  void recordOrderCreated_createsSagaStateAndAssignsSagaId() {
    // given
    Order order = createOrder();
    UUID orderId = UUID.randomUUID();
    ReflectionTestUtils.setField(order, "id", orderId);

    // when
    orderSagaRecorder.recordOrderCreated(order);

    // then
    assertThat(order.getSagaId()).isEqualTo(orderId.toString());
    ArgumentCaptor<OrderSagaState> captor = ArgumentCaptor.forClass(OrderSagaState.class);
    verify(orderSagaStateRepository).save(captor.capture());
    assertThat(captor.getValue().getOrderId()).isEqualTo(orderId);
    assertThat(captor.getValue().getSagaId()).isEqualTo(orderId.toString());
    assertThat(captor.getValue().getCurrentStep()).isEqualTo(OrderSagaStep.ORDER_CREATED);
  }

  @Test
  @DisplayName("재고 차감 성공 후 사가 단계를 STOCK_DECREASED로 갱신한다")
  void recordStockDecreased_whenSagaExists_advancesStep() {
    // given
    UUID orderId = UUID.randomUUID();
    OrderSagaState sagaState = existingSagaState(orderId);
    when(orderSagaStateRepository.findByOrderId(orderId)).thenReturn(Optional.of(sagaState));

    // when
    orderSagaRecorder.recordStockDecreased(orderId);

    // then
    assertThat(sagaState.getCurrentStep()).isEqualTo(OrderSagaStep.STOCK_DECREASED);
  }

  @Test
  @DisplayName("결제 완료 처리 시 사가 단계를 COMPLETED로 갱신한다")
  void recordCompleted_whenSagaExists_advancesStep() {
    // given
    UUID orderId = UUID.randomUUID();
    OrderSagaState sagaState = existingSagaState(orderId);
    when(orderSagaStateRepository.findByOrderId(orderId)).thenReturn(Optional.of(sagaState));

    // when
    orderSagaRecorder.recordCompleted(orderId);

    // then
    assertThat(sagaState.getCurrentStep()).isEqualTo(OrderSagaStep.COMPLETED);
  }

  @Test
  @DisplayName("취소로 보상이 시작되면 사가 단계를 COMPENSATING으로, 롤백 성공 후 COMPENSATION_COMPLETED로 갱신한다")
  void compensationFlow_advancesThroughCompensatingThenCompleted() {
    // given
    UUID orderId = UUID.randomUUID();
    OrderSagaState sagaState = existingSagaState(orderId);
    when(orderSagaStateRepository.findByOrderId(orderId)).thenReturn(Optional.of(sagaState));
    doAnswer(
            invocation -> {
              sagaState.enterCompensating();
              return 1;
            })
        .when(orderSagaStateRepository)
        .enterCompensatingUnlessCompleted(eq(orderId), any(Instant.class));

    // when
    orderSagaRecorder.recordCompensating(orderId);

    // then
    assertThat(sagaState.getCurrentStep()).isEqualTo(OrderSagaStep.COMPENSATING);
    assertThat(sagaState.getCompensatingSince()).isNotNull();
    Instant firstCompensatingSince = sagaState.getCompensatingSince();

    orderSagaRecorder.recordCompensating(orderId);
    assertThat(sagaState.getCompensatingSince()).isEqualTo(firstCompensatingSince);

    // when
    orderSagaRecorder.recordCompensationCompleted(orderId);

    // then
    assertThat(sagaState.getCurrentStep()).isEqualTo(OrderSagaStep.COMPENSATION_COMPLETED);
  }

  @Test
  @DisplayName("사가 row가 없는 레거시 주문 이벤트는 예외 없이 스킵하고 정상 진행한다")
  void advance_whenSagaStateMissing_skipsWithoutException() {
    // given
    UUID orderId = UUID.randomUUID();
    when(orderSagaStateRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

    // when & then
    assertThatCode(
            () -> {
              orderSagaRecorder.recordStockDecreased(orderId);
              orderSagaRecorder.recordCompleted(orderId);
              orderSagaRecorder.recordCompensating(orderId);
              orderSagaRecorder.recordCompensationCompleted(orderId);
            })
        .doesNotThrowAnyException();
    verify(orderSagaStateRepository, never()).save(any());
  }

  @Test
  void should_not_regress_compensating_saga_to_stock_decreased() {
    UUID orderId = UUID.randomUUID();
    OrderSagaState sagaState = existingSagaState(orderId);
    sagaState.enterCompensating();
    when(orderSagaStateRepository.findByOrderId(orderId)).thenReturn(Optional.of(sagaState));

    orderSagaRecorder.recordStockDecreased(orderId);

    assertThat(sagaState.getCurrentStep()).isEqualTo(OrderSagaStep.COMPENSATING);
  }

  @Test
  void should_not_regress_completed_compensation_to_stock_decreased() {
    UUID orderId = UUID.randomUUID();
    OrderSagaState sagaState = existingSagaState(orderId);
    sagaState.advanceTo(OrderSagaStep.COMPENSATION_COMPLETED);
    when(orderSagaStateRepository.findByOrderId(orderId)).thenReturn(Optional.of(sagaState));

    orderSagaRecorder.recordStockDecreased(orderId);

    assertThat(sagaState.getCurrentStep()).isEqualTo(OrderSagaStep.COMPENSATION_COMPLETED);
  }

  @Test
  void should_not_regress_completed_compensation_to_compensating() {
    UUID orderId = UUID.randomUUID();
    OrderSagaState sagaState = existingSagaState(orderId);
    sagaState.advanceTo(OrderSagaStep.COMPENSATION_COMPLETED);
    when(orderSagaStateRepository.enterCompensatingUnlessCompleted(
            eq(orderId), any(Instant.class)))
        .thenReturn(0);

    orderSagaRecorder.recordCompensating(orderId);

    assertThat(sagaState.getCurrentStep()).isEqualTo(OrderSagaStep.COMPENSATION_COMPLETED);
    verify(orderSagaStateRepository)
        .enterCompensatingUnlessCompleted(eq(orderId), any(Instant.class));
  }

  @Test
  void should_ignore_equal_and_regressive_saga_advances() {
    UUID orderId = UUID.randomUUID();
    OrderSagaState sagaState = existingSagaState(orderId);
    sagaState.advanceTo(OrderSagaStep.COMPENSATION_COMPLETED);
    when(orderSagaStateRepository.findByOrderId(orderId)).thenReturn(Optional.of(sagaState));

    orderSagaRecorder.recordStockDecreased(orderId);
    orderSagaRecorder.recordCompleted(orderId);
    orderSagaRecorder.recordCompensationCompleted(orderId);

    assertThat(sagaState.getCurrentStep()).isEqualTo(OrderSagaStep.COMPENSATION_COMPLETED);
  }

  private OrderSagaState existingSagaState(UUID orderId) {
    return OrderSagaState.create().orderId(orderId).sagaId(orderId.toString()).build();
  }

  private Order createOrder() {
    return Order.create()
        .orderNumber("ORD-20260626-0001")
        .memberId(UUID.randomUUID())
        .dropId(UUID.randomUUID())
        .productId(UUID.randomUUID())
        .sellerId(UUID.randomUUID())
        .productName("테스트 상품")
        .quantity(1)
        .unitPrice(1_000L)
        .idempotencyKey("idem-001")
        .now(Instant.parse("2026-06-26T00:00:00Z"))
        .build();
  }
}
