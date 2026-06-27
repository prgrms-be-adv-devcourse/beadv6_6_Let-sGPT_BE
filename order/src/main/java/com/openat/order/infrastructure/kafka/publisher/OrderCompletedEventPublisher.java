package com.openat.order.infrastructure.kafka.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.application.port.OrderCompletedEventPublishPort;
import com.openat.order.domain.model.Order;
import com.openat.order.infrastructure.kafka.event.OrderCompletedEvent;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderCompletedEventPublisher implements OrderCompletedEventPublishPort {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public OrderCompletedEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${order.kafka.topic.order-completed}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @Override
    public void publish(Order order) {
        String key = order.getId().toString();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new OrderCompletedEvent(
                    order.getId(),
                    order.getSellerId(),
                    order.getProductId(),
                    order.getMemberId(),
                    order.getTotalPrice(),
                    order.getCompletedAt()
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("주문 완료 이벤트 payload 생성에 실패했습니다.", e);
        }

        try {
            RecordMetadata metadata = publishBlocking(key, payload);
            log.info("order_completed.events published. orderId={}, topic={}, partition={}, offset={}",
                    order.getId(), topic, metadata.partition(), metadata.offset());
        } catch (Exception e) {
            throw new IllegalStateException("order_completed.events 발행에 실패했습니다.", e);
        }
    }

    private RecordMetadata publishBlocking(String key, String payload) throws Exception {
        return kafkaTemplate.send(topic, key, payload)
                .get(5, TimeUnit.SECONDS)
                .getRecordMetadata();
    }
}
