package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.application.dto.CreateOrderCommand;
import com.openat.order.application.dto.OrderSnapshotInfo;
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

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private OrderHistoryRepository orderHistoryRepository;

    @Mock
    private OrderSagaRecorder orderSagaRecorder;

    @InjectMocks
    private PendingOrderCreator pendingOrderCreator;

    @Test
    @DisplayName("주문 생성은 주문 저장과 같은 트랜잭션에서 사가 ORDER_CREATED 기록을 요청한다")
    void create_recordsOrderCreatedSagaWithSavedOrder() {
        // given
        UUID memberId = UUID.randomUUID();
        CreateOrderCommand command = new CreateOrderCommand(UUID.randomUUID(), 2, "idem-001", "테스트 상품");
        OrderSnapshotInfo snapshot = new OrderSnapshotInfo(UUID.randomUUID(), UUID.randomUUID(), 10_000L);
        Instant now = Instant.parse("2026-06-26T00:00:00Z");
        UUID generatedId = UUID.randomUUID();

        when(orderRepository.saveAndFlush(any(Order.class))).thenAnswer(invocation -> {
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
}
