package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.CreateOrderCommand;
import com.openat.order.application.dto.CreateOrderResult;
import com.openat.order.application.dto.OrderSnapshotInfo;
import com.openat.order.application.dto.StockDecreaseCommand;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderCreationServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private ProductIntegrationPort productIntegrationPort;

    @Mock
    private PendingOrderCreator pendingOrderCreator;

    @Mock
    private OrderFailureRecorder orderFailureRecorder;

    @Mock
    private OrderSagaRecorder orderSagaRecorder;

    @InjectMocks
    private OrderCreationService orderCreationService;

    @Test
    @DisplayName("주문 생성 시 상품 재고 감소 요청에 주문 식별자와 구매자 식별자를 전달한다")
    void createOrder_decreasesStockWithOrderIdAndBuyerId() {
        // given
        UUID memberId = UUID.randomUUID();
        CreateOrderCommand command = new CreateOrderCommand(UUID.randomUUID(), 2, "idem-001", "테스트 상품");
        OrderSnapshotInfo snapshot = snapshot(command.dropId());
        Order order = createOrder(memberId, command.dropId(), snapshot, command.quantity(), command.idempotencyKey());

        when(orderRepository.findByMemberIdAndIdempotencyKey(memberId, command.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(productIntegrationPort.fetchOrderSnapshot(command.dropId())).thenReturn(snapshot);
        when(pendingOrderCreator.create(any(), any(), any(), any())).thenReturn(order);

        // when
        CreateOrderResult result = orderCreationService.create(memberId, command);

        // then
        assertThat(result.orderId()).isEqualTo(order.getId());
        assertThat(result.created()).isTrue();
        ArgumentCaptor<StockDecreaseCommand> stockCommand = ArgumentCaptor.forClass(StockDecreaseCommand.class);
        verify(productIntegrationPort).decreaseStock(any(), stockCommand.capture());
        assertThat(stockCommand.getValue().orderId()).isEqualTo(order.getId());
        assertThat(stockCommand.getValue().buyerId()).isEqualTo(memberId);
        assertThat(stockCommand.getValue().quantity()).isEqualTo(command.quantity());
        verify(orderSagaRecorder).recordStockDecreased(order.getId());
    }

    @Test
    @DisplayName("같은 멱등키의 기존 주문이 있으면 상품 API를 다시 호출하지 않는다")
    void createOrder_whenExistingIdempotencyKey_returnExistingOrder() {
        // given
        UUID memberId = UUID.randomUUID();
        CreateOrderCommand command = new CreateOrderCommand(UUID.randomUUID(), 1, "idem-001", "테스트 상품");
        Order existing = createOrder(memberId, command.dropId(), snapshot(command.dropId()), command.quantity(), command.idempotencyKey());

        when(orderRepository.findByMemberIdAndIdempotencyKey(memberId, command.idempotencyKey()))
                .thenReturn(Optional.of(existing));

        // when
        CreateOrderResult result = orderCreationService.create(memberId, command);

        // then
        assertThat(result.orderId()).isEqualTo(existing.getId());
        assertThat(result.created()).isFalse();
        verify(productIntegrationPort, never()).fetchOrderSnapshot(any());
        verify(productIntegrationPort, never()).decreaseStock(any(), any());
    }

    @Test
    @DisplayName("같은 멱등키의 기존 주문과 요청 내용이 다르면 충돌을 반환한다")
    void createOrder_whenExistingIdempotencyKeyWithDifferentBody_throwsConflict() {
        // given
        UUID memberId = UUID.randomUUID();
        UUID originalDropId = UUID.randomUUID();
        CreateOrderCommand command = new CreateOrderCommand(UUID.randomUUID(), 1, "idem-001", "테스트 상품");
        Order existing = createOrder(memberId, originalDropId, snapshot(originalDropId), command.quantity(), command.idempotencyKey());

        when(orderRepository.findByMemberIdAndIdempotencyKey(memberId, command.idempotencyKey()))
                .thenReturn(Optional.of(existing));

        // when
        BusinessException ex = assertThrows(BusinessException.class, () -> orderCreationService.create(memberId, command));

        // then
        assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.IDEMPOTENCY_CONFLICT);
        verify(productIntegrationPort, never()).fetchOrderSnapshot(any());
        verify(productIntegrationPort, never()).decreaseStock(any(), any());
    }

    @Test
    @DisplayName("같은 멱등키의 기존 주문이 실패 상태면 성공 재응답 대신 원래 실패 에러를 반환한다")
    void createOrder_whenExistingOrderFailed_throwsOriginalFailure() {
        // given
        UUID memberId = UUID.randomUUID();
        CreateOrderCommand command = new CreateOrderCommand(UUID.randomUUID(), 1, "idem-001", "테스트 상품");
        Order existing = createOrder(memberId, command.dropId(), snapshot(command.dropId()), command.quantity(), command.idempotencyKey());
        existing.fail(OrderFailCode.SOLD_OUT, "재고가 없습니다.", Instant.parse("2026-06-26T00:01:00Z"));

        when(orderRepository.findByMemberIdAndIdempotencyKey(memberId, command.idempotencyKey()))
                .thenReturn(Optional.of(existing));

        // when
        BusinessException ex = assertThrows(BusinessException.class, () -> orderCreationService.create(memberId, command));

        // then
        assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.SOLD_OUT);
        verify(productIntegrationPort, never()).fetchOrderSnapshot(any());
        verify(productIntegrationPort, never()).decreaseStock(any(), any());
    }

    @Test
    @DisplayName("주문 생성 시 주문 표시명이 없으면 잘못된 요청으로 처리한다")
    void createOrder_whenOrderNameMissing_throwsInvalidInput() {
        // given
        UUID memberId = UUID.randomUUID();
        CreateOrderCommand command = new CreateOrderCommand(UUID.randomUUID(), 1, "idem-001", " ");

        // when
        BusinessException ex = assertThrows(BusinessException.class, () -> orderCreationService.create(memberId, command));

        // then
        assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.INVALID_INPUT);
        verify(productIntegrationPort, never()).fetchOrderSnapshot(any());
        verify(productIntegrationPort, never()).decreaseStock(any(), any());
    }

    @Test
    @DisplayName("동시 주문 생성으로 멱등키 유니크 충돌이 발생하면 기존 주문을 반환하고 재고를 다시 차감하지 않는다")
    void createOrder_whenConcurrentSameIdempotencyKey_returnExistingOrderWithoutStockDecrease() {
        // given
        UUID memberId = UUID.randomUUID();
        CreateOrderCommand command = new CreateOrderCommand(UUID.randomUUID(), 1, "idem-001", "테스트 상품");
        OrderSnapshotInfo snapshot = snapshot(command.dropId());
        Order existing = createOrder(memberId, command.dropId(), snapshot, command.quantity(), command.idempotencyKey());

        when(orderRepository.findByMemberIdAndIdempotencyKey(memberId, command.idempotencyKey()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(productIntegrationPort.fetchOrderSnapshot(command.dropId())).thenReturn(snapshot);
        when(pendingOrderCreator.create(eq(memberId), eq(command), eq(snapshot), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate idempotency key"));

        // when
        CreateOrderResult result = orderCreationService.create(memberId, command);

        // then
        assertThat(result.orderId()).isEqualTo(existing.getId());
        assertThat(result.created()).isFalse();
        verify(productIntegrationPort, never()).decreaseStock(any(), any());
    }

    @Test
    @DisplayName("재고 감소 실패 시 주문 실패 이력을 기록하고 주문 오류로 변환한다")
    void createOrder_whenStockDecreaseFails_recordsFailureAndThrowsOrderError() {
        // given
        UUID memberId = UUID.randomUUID();
        CreateOrderCommand command = new CreateOrderCommand(UUID.randomUUID(), 1, "idem-001", "테스트 상품");
        OrderSnapshotInfo snapshot = snapshot(command.dropId());
        Order order = createOrder(memberId, command.dropId(), snapshot, command.quantity(), command.idempotencyKey());

        when(orderRepository.findByMemberIdAndIdempotencyKey(memberId, command.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(productIntegrationPort.fetchOrderSnapshot(command.dropId())).thenReturn(snapshot);
        when(pendingOrderCreator.create(any(), any(), any(), any())).thenReturn(order);
        doThrow(new ProductPortException(OrderFailCode.SOLD_OUT, "sold out"))
                .when(productIntegrationPort)
                .decreaseStock(any(), any());

        // when
        BusinessException ex = assertThrows(BusinessException.class, () -> orderCreationService.create(memberId, command));

        // then
        assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.SOLD_OUT);
        verify(orderFailureRecorder).recordCreateFailure(any(), any(), any(), any());
        verify(orderSagaRecorder, never()).recordStockDecreased(any());
    }

    @Test
    @DisplayName("드롭 종료로 재고 감소가 실패하면 닫힌 드롭 오류로 변환한다")
    void createOrder_whenDropClosed_throwsDropClosedError() {
        // given
        UUID memberId = UUID.randomUUID();
        CreateOrderCommand command = new CreateOrderCommand(UUID.randomUUID(), 1, "idem-001", "테스트 상품");
        OrderSnapshotInfo snapshot = snapshot(command.dropId());
        Order order = createOrder(memberId, command.dropId(), snapshot, command.quantity(), command.idempotencyKey());

        when(orderRepository.findByMemberIdAndIdempotencyKey(memberId, command.idempotencyKey()))
                .thenReturn(Optional.empty());
        when(productIntegrationPort.fetchOrderSnapshot(command.dropId())).thenReturn(snapshot);
        when(pendingOrderCreator.create(any(), any(), any(), any())).thenReturn(order);
        doThrow(new ProductPortException(OrderFailCode.DROP_CLOSED, "drop closed"))
                .when(productIntegrationPort)
                .decreaseStock(any(), any());

        // when
        BusinessException ex = assertThrows(BusinessException.class, () -> orderCreationService.create(memberId, command));

        // then
        assertThat(ex.getErrorCode()).isEqualTo(OrderErrorCode.DROP_CLOSED);
        verify(orderFailureRecorder).recordCreateFailure(any(), any(), any(), any());
    }

    private OrderSnapshotInfo snapshot(UUID dropId) {
        return new OrderSnapshotInfo(UUID.randomUUID(), UUID.randomUUID(), 10_000L);
    }

    private Order createOrder(UUID memberId, UUID dropId, OrderSnapshotInfo snapshot, int quantity, String idempotencyKey) {
        Order order = Order.create()
                .orderNumber("ORD-20260626-0001")
                .memberId(memberId)
                .dropId(dropId)
                .productId(snapshot.productId())
                .sellerId(snapshot.sellerId())
                .productName("테스트 상품")
                .quantity(quantity)
                .unitPrice(snapshot.unitPrice())
                .idempotencyKey(idempotencyKey)
                .now(Instant.parse("2026-06-26T00:00:00Z"))
                .build();
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        return order;
    }
}
