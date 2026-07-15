package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.OrderSnapshotInfo;
import com.openat.order.application.dto.StockRestoreCommand;
import com.openat.order.application.port.ProductIntegrationPort;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
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
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderCancellationServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderHistoryRecorder orderHistoryRecorder;

    @Mock
    private ProductIntegrationPort productIntegrationPort;

    @Mock
    private OrderSagaRecorder orderSagaRecorder;

    @Mock
    private OrderCompensationFailureRecorder compensationFailureRecorder;

    @InjectMocks
    private OrderCancellationService orderCancellationService;

    @Test
    @DisplayName("결제 대기 주문 취소 시 재고를 복구하고 취소 이력을 남긴다")
    void cancelPaymentPendingOrder_restoresStockAndRecordsHistory() {
        UUID memberId = UUID.randomUUID();
        Order order = createOrder(memberId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        orderCancellationService.cancel(memberId, order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        ArgumentCaptor<StockRestoreCommand> command = ArgumentCaptor.forClass(StockRestoreCommand.class);
        verify(productIntegrationPort).restoreStock(any(), command.capture());
        assertThat(command.getValue().orderId()).isEqualTo(order.getId());
        assertThat(command.getValue().buyerId()).isEqualTo(memberId);
        assertThat(command.getValue().quantity()).isEqualTo(order.getQuantity());
        verify(orderHistoryRecorder).record(any(), any(), any(), any(), any());
        verify(orderSagaRecorder).recordCompensating(order.getId());
        verify(orderSagaRecorder).recordCompensationCompleted(order.getId());
    }

    @Test
    @DisplayName("완료 주문 취소 요청은 환불 요청 상태로 바꾸고 재고를 즉시 복구하지 않는다")
    void cancelCompletedOrder_requestsRefundWithoutRestoringStock() {
        UUID memberId = UUID.randomUUID();
        Order order = createOrder(memberId);
        order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:01:00Z"));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        orderCancellationService.cancel(memberId, order.getId());

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCEL_REQUESTED);
        verify(productIntegrationPort, never()).restoreStock(any(), any());
        verify(orderHistoryRecorder).record(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("재고 롤백 실패 시 보상 실패를 기록하고 사가를 COMPENSATING에 유지한다")
    void cancelPaymentPendingOrder_whenStockRestoreFails_recordsCompensationFailure() {
        UUID memberId = UUID.randomUUID();
        Order order = createOrder(memberId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        doThrow(new ProductPortException(OrderFailCode.STOCK_ROLLBACK_FAILED, "rollback failed"))
                .when(productIntegrationPort)
                .restoreStock(any(), any());

        assertThrows(BusinessException.class, () -> orderCancellationService.cancel(memberId, order.getId()));

        verify(orderSagaRecorder).recordCompensating(order.getId());
        verify(orderSagaRecorder, never()).recordCompensationCompleted(order.getId());
        verify(compensationFailureRecorder).recordStockRollbackFailure(order.getId(), "rollback failed");
    }

    @Test
    @DisplayName("주문 소유자가 아니면 취소를 거부한다")
    void cancelOrder_rejectsNonOwner() {
        Order order = createOrder(UUID.randomUUID());
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> orderCancellationService.cancel(UUID.randomUUID(), order.getId()));

        assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.NOT_OWNER);
        verify(productIntegrationPort, never()).restoreStock(any(), any());
    }

    private Order createOrder(UUID memberId) {
        UUID dropId = UUID.randomUUID();
        OrderSnapshotInfo snapshot = new OrderSnapshotInfo(UUID.randomUUID(), UUID.randomUUID(), 10_000L);
        Order order = Order.create()
                .orderNumber("ORD-20260626-0001")
                .memberId(memberId)
                .dropId(dropId)
                .productId(snapshot.productId())
                .sellerId(snapshot.sellerId())
                .productName("테스트 상품")
                .quantity(2)
                .unitPrice(snapshot.unitPrice())
                .idempotencyKey("idem-001")
                .now(Instant.parse("2026-06-26T00:00:00Z"))
                .build();
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        return order;
    }
}
