package com.openat.order.infrastructure.event;

import com.openat.order.application.port.OrderCompletedEventPublisher;
import com.openat.order.domain.model.Order;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KafkaOrderCompletedEventPublisher implements OrderCompletedEventPublisher {

    private static final String TOPIC = "order_completed.events";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final Clock clock;

    @Override
    public void publish(Order order) {
        OrderCompletedPayload payload = new OrderCompletedPayload(
                order.getId(),
                order.getSellerId(),
                order.getProductId(),
                order.getMemberId(),
                order.getTotalPrice(),
                order.getQuantity(),
                order.getCompletedAt());
        IntegrationEvent<OrderCompletedPayload> event = new IntegrationEvent<>(
                "evt-order-" + UUID.randomUUID(),
                "OrderCompletedIntegrationEvent",
                "1.0",
                clock.instant(),
                "order-service",
                "ORDER",
                order.getId().toString(),
                null,
                payload);
        kafkaTemplate.send(TOPIC, order.getId().toString(), event);
    }
}
