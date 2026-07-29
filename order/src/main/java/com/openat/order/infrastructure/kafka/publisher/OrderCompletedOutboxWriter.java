package com.openat.order.infrastructure.kafka.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.application.port.OrderCompletedOutboxPort;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OutboxEvent;
import com.openat.order.domain.repository.OutboxEventRepository;
import com.openat.order.infrastructure.kafka.event.OrderCompletedEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OrderCompletedOutboxWriter implements OrderCompletedOutboxPort {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final String topic;

    public OrderCompletedOutboxWriter(
            OutboxEventRepository outboxEventRepository,
            ObjectMapper objectMapper,
            @Value("${order.kafka.topic.order-completed}") String topic
    ) {
        this.outboxEventRepository = outboxEventRepository;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void save(Order order) {
        outboxEventRepository.save(
                OutboxEvent.create()
                        .topic(topic)
                        .payload(serialize(order))
                        // 원 요청 트랜잭션 안에서 현재 traceparent를 캡처해 저장한다. 발행은 폴링이라
                        // 이 값 없이는 producer 스팬이 폴링 tick의 자식이 되어 원 요청과 끊긴다.
                        .traceParent(OutboxTracePropagation.currentTraceParent())
                        .build());
    }

    private String serialize(Order order) {
        try {
            return objectMapper.writeValueAsString(new OrderCompletedEvent(
                    order.getId(),
                    order.getSellerId(),
                    order.getProductId(),
                    order.getMemberId(),
                    order.getTotalPrice()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("주문 완료 이벤트 payload 생성에 실패했습니다.", exception);
        }
    }
}
