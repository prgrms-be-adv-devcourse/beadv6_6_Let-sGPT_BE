package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.OrderSnapshotInfo;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.model.PurchaseSignal;
import com.openat.order.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderCreationService orderCreationService;

    @Mock
    private OrderCancellationService orderCancellationService;

    @InjectMocks
    private OrderService orderService;

    @Test
    @DisplayName("결제 검증 조회는 주문 금액과 상태를 반환한다")
    void getPaymentValidationInfo_returnsAmountAndStatus() {
        UUID memberId = UUID.randomUUID();
        Order order = createOrder(memberId);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        var result = orderService.getPaymentValidationInfo(memberId, order.getId());

        assertThat(result.orderId()).isEqualTo(order.getId());
        assertThat(result.memberId()).isEqualTo(memberId);
        assertThat(result.amount()).isEqualTo(order.getTotalPrice());
        assertThat(result.status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(result.paymentExpiresAt()).isEqualTo(order.getPaymentExpiresAt());
    }

    @Test
    @DisplayName("결제 검증 조회는 주문 소유자가 아니면 거부한다")
    void getPaymentValidationInfo_rejectsNonOwner() {
        Order order = createOrder(UUID.randomUUID());
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> orderService.getPaymentValidationInfo(UUID.randomUUID(), order.getId()));

        assertThat(exception.getErrorCode()).isEqualTo(OrderErrorCode.NOT_OWNER);
    }

    @Test
    @DisplayName("구매 신호는 완료 주문만 상품별 집계해 최신 주문순으로 제한한다")
    void getPurchaseSignals_returnsCompletedAggregatesInRecencyOrderWithLimit() {
        UUID memberId = UUID.randomUUID();
        UUID recentProductId = UUID.randomUUID();
        UUID olderProductId = UUID.randomUUID();
        Instant recent = Instant.parse("2026-07-15T10:00:00Z");
        Instant older = Instant.parse("2026-07-10T10:00:00Z");
        List<PurchaseSignal> aggregates = List.of(
                new PurchaseSignal(recentProductId, 2, 5, recent),
                new PurchaseSignal(olderProductId, 1, 2, older));
        when(orderRepository.findPurchaseSignals(
                memberId, OrderStatus.COMPLETED, PageRequest.of(0, 2)))
                .thenReturn(aggregates);

        var result = orderService.getPurchaseSignals(memberId, 2);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).productId()).isEqualTo(recentProductId);
        assertThat(result.get(0).orderCount()).isEqualTo(2);
        assertThat(result.get(0).totalQuantity()).isEqualTo(5);
        assertThat(result.get(0).lastOrderedAt()).isEqualTo(recent);
        assertThat(result.get(1).productId()).isEqualTo(olderProductId);
        verify(orderRepository).findPurchaseSignals(
                memberId, OrderStatus.COMPLETED, PageRequest.of(0, 2));
    }

    private Order createOrder(UUID memberId) {
        OrderSnapshotInfo snapshot = new OrderSnapshotInfo(UUID.randomUUID(), UUID.randomUUID(), 10_000L);
        Order order = Order.create()
                .orderNumber("ORD-20260626-0001")
                .memberId(memberId)
                .dropId(UUID.randomUUID())
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
