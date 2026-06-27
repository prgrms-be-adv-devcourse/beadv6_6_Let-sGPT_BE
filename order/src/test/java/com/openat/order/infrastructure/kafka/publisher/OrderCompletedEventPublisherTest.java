package com.openat.order.infrastructure.kafka.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openat.order.domain.model.Order;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

class OrderCompletedEventPublisherTest {

    private static final String TOPIC = "order_completed.events";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Test
    @DisplayName("주문 완료 이벤트를 지정된 topic과 orderId key로 발행한다")
    void publish_sendsOrderCompletedEvent() throws Exception {
        // given
        CapturingKafkaTemplate kafkaTemplate = new CapturingKafkaTemplate(false);
        OrderCompletedEventPublisher publisher = new OrderCompletedEventPublisher(kafkaTemplate, objectMapper, TOPIC);
        Order order = completedOrder();

        // when
        publisher.publish(order);

        // then
        assertThat(kafkaTemplate.topic).isEqualTo(TOPIC);
        assertThat(kafkaTemplate.key).isEqualTo(order.getId().toString());

        JsonNode json = objectMapper.readTree(kafkaTemplate.payload);
        assertThat(json.get("orderId").asText()).isEqualTo(order.getId().toString());
        assertThat(json.get("sellerId").asText()).isEqualTo(order.getSellerId().toString());
        assertThat(json.get("productId").asText()).isEqualTo(order.getProductId().toString());
        assertThat(json.get("memberId").asText()).isEqualTo(order.getMemberId().toString());
        assertThat(json.get("amount").asLong()).isEqualTo(order.getTotalPrice());
        assertThat(json.has("completedAt")).isFalse();
    }

    @Test
    @DisplayName("주문 완료 이벤트 발행이 실패하면 예외를 던진다")
    void publish_whenKafkaSendFails_throwIllegalStateException() {
        // given
        CapturingKafkaTemplate kafkaTemplate = new CapturingKafkaTemplate(true);
        OrderCompletedEventPublisher publisher = new OrderCompletedEventPublisher(kafkaTemplate, objectMapper, TOPIC);
        Order order = completedOrder();

        // when & then
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> publisher.publish(order));
        assertThat(ex.getMessage()).isEqualTo("order_completed.events 발행에 실패했습니다.");
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

    private static class CapturingKafkaTemplate extends KafkaTemplate<String, String> {

        private final boolean fail;
        private String topic;
        private String key;
        private String payload;

        @SuppressWarnings("unchecked")
        CapturingKafkaTemplate(boolean fail) {
            super(org.mockito.Mockito.mock(ProducerFactory.class));
            this.fail = fail;
        }

        @Override
        public CompletableFuture<SendResult<String, String>> send(String topic, String key, String data) {
            this.topic = topic;
            this.key = key;
            this.payload = data;

            CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
            if (fail) {
                future.completeExceptionally(new RuntimeException("kafka unavailable"));
                return future;
            }

            ProducerRecord<String, String> producerRecord = new ProducerRecord<>(topic, key, data);
            RecordMetadata metadata = new RecordMetadata(new TopicPartition(topic, 0), 1L, 0, 0L, 0, 0);
            future.complete(new SendResult<>(producerRecord, metadata));
            return future;
        }
    }
}
