package com.openat.payment.infrastructure.devtools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.payment.application.dto.DummyPaymentCompletedEvent;
import com.openat.payment.application.dto.DummyPaymentRefundedEvent;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// 정산팀 컨슈머 테스트 전용 — Outbox 미경유, 호출 즉시 Kafka로 발행(personal_workplan/research.md §18.3).
@Profile("local")
@Component
public class DummySettlementEventPublisher {

    private static final String PAYMENT_TOPIC = "payment.settlement-source.events";
    private static final String REFUND_TOPIC = "refund.settlement-source.events";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public DummySettlementEventPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishPaymentCompleted(DummyPaymentCompletedEvent event) {
        send(PAYMENT_TOPIC, event.paymentId(), event);
    }

    public void publishPaymentRefunded(DummyPaymentRefundedEvent event) {
        send(REFUND_TOPIC, event.paymentId(), event);
    }

    private void send(String topic, UUID key, Object payload) {
        try {
            kafkaTemplate.send(topic, key.toString(), objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new IllegalStateException("더미 정산이벤트 발행 실패: " + topic, e);
        }
    }
}
