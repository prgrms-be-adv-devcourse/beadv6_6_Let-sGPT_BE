package com.openat.order.infrastructure.kafka.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OutboxEvent;
import com.openat.order.domain.model.OutboxEventStatus;
import com.openat.order.domain.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class OrderCompletedOutboxWriterTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Test
    @DisplayName("주문 완료 이벤트를 PENDING Outbox 행으로 저장한다")
    void save_createsPendingOutboxEvent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OrderCompletedOutboxWriter writer = new OrderCompletedOutboxWriter(
                outboxEventRepository, objectMapper, "order.completed.events");
        Order order = completedOrder();

        writer.save(order);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getTopic()).isEqualTo("order.completed.events");
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(objectMapper.readTree(event.getPayload()).get("orderId").asText())
                .isEqualTo(order.getId().toString());
    }

    private Order completedOrder() {
        Order order = Order.create()
                .orderNumber("ORD-20260626-0001")
                .memberId(UUID.randomUUID())
                .dropId(UUID.randomUUID())
                .productId(UUID.randomUUID())
                .sellerId(UUID.randomUUID())
                .productName("테스트 상품")
                .quantity(2)
                .unitPrice(5_000L)
                .idempotencyKey("idem-001")
                .now(Instant.parse("2026-06-26T00:00:00Z"))
                .build();
        ReflectionTestUtils.setField(order, "id", UUID.randomUUID());
        order.complete(UUID.randomUUID(), Instant.parse("2026-06-26T00:00:01Z"));
        return order;
    }
}
