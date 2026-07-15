package com.openat.order.infrastructure.kafka.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.application.event.StockAdjustment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
public class StockAdjustmentEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;

    public StockAdjustmentEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Value("${order.kafka.topic.stock-adjusted}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(StockAdjustment adjustment) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(adjustment);
        } catch (JsonProcessingException exception) {
            log.error("Stock adjustment serialization failed. eventId={}, dropId={}",
                    adjustment.eventId(), adjustment.dropId(), exception);
            return;
        }

        try {
            kafkaTemplate.send(topic, adjustment.dropId().toString(), payload)
                    .whenComplete((result, exception) -> {
                        if (exception != null) {
                            log.error("Stock adjustment publish failed. eventId={}, dropId={}, topic={}",
                                    adjustment.eventId(), adjustment.dropId(), topic, exception);
                            return;
                        }
                        log.info("Stock adjustment published. eventId={}, dropId={}, reason={}, topic={}",
                                adjustment.eventId(), adjustment.dropId(), adjustment.reason(), topic);
                    });
        } catch (RuntimeException exception) {
            log.error("Stock adjustment publish failed. eventId={}, dropId={}, topic={}",
                    adjustment.eventId(), adjustment.dropId(), topic, exception);
        }
    }
}
