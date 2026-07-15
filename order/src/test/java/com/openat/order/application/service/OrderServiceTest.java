package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.OrderSnapshotInfo;
import com.openat.order.domain.exception.OrderErrorCode;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.repository.OrderRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
