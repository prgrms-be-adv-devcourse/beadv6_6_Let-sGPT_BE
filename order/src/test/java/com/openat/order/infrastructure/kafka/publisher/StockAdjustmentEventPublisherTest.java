package com.openat.order.infrastructure.kafka.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.application.event.StockAdjustment;
import com.openat.order.application.event.StockAdjustmentReason;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import java.util.Map;

@ExtendWith(MockitoExtension.class)
class StockAdjustmentEventPublisherTest {

    private static final String TOPIC = "order.stock.adjusted.events";

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
        if (context != null) {
            context.close();
        }
    }

    @Test
    @DisplayName("StockAdjustment는 트랜잭션 커밋 전에는 발행하지 않고 커밋 후 직접 발행한다")
    void publish_sendsOnlyAfterCommit() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        when(kafkaTemplate.send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("test", Map.of("order.kafka.topic.stock-adjusted", TOPIC)));
        context.registerBean(KafkaTemplate.class, () -> kafkaTemplate);
        context.registerBean(ObjectMapper.class, () -> objectMapper);
        context.registerBean(PlatformTransactionManager.class, TestTransactionManager::new);
        context.register(TestConfiguration.class);
        context.refresh();
        StockAdjustment adjustment = new StockAdjustment(
                UUID.randomUUID(), UUID.randomUUID(), 3, StockAdjustmentReason.COMPLETED);

        TransactionTemplate transactionTemplate = new TransactionTemplate(
                context.getBean(PlatformTransactionManager.class));
        transactionTemplate.executeWithoutResult(status -> {
            context.publishEvent(adjustment);
            verify(kafkaTemplate, never()).send(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString());
        });

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq(TOPIC),
                org.mockito.ArgumentMatchers.eq(adjustment.dropId().toString()),
                payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.get("eventId").asText()).isEqualTo(adjustment.eventId().toString());
        assertThat(json.get("dropId").asText()).isEqualTo(adjustment.dropId().toString());
        assertThat(json.get("count").asInt()).isEqualTo(3);
        assertThat(json.get("reason").asText()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("REFUNDED StockAdjustment payload는 복원 수량과 사유를 그대로 발행한다")
    void publish_refundedPayloadContainsCountAndReason() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        when(kafkaTemplate.send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        StockAdjustmentEventPublisher publisher =
                new StockAdjustmentEventPublisher(kafkaTemplate, objectMapper, TOPIC);
        StockAdjustment adjustment = new StockAdjustment(
                UUID.randomUUID(), UUID.randomUUID(), 2, StockAdjustmentReason.REFUNDED);

        publisher.publish(adjustment);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq(TOPIC),
                org.mockito.ArgumentMatchers.eq(adjustment.dropId().toString()),
                payload.capture());
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.get("count").asInt()).isEqualTo(2);
        assertThat(json.get("reason").asText()).isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("Kafka send가 동기 예외를 던져도 발행 실패를 호출자에게 전파하지 않는다")
    void publish_whenSendThrowsSynchronously_doesNotPropagateException() {
        ObjectMapper objectMapper = new ObjectMapper();
        when(kafkaTemplate.send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalStateException("metadata timeout"));
        StockAdjustmentEventPublisher publisher =
                new StockAdjustmentEventPublisher(kafkaTemplate, objectMapper, TOPIC);
        StockAdjustment adjustment = new StockAdjustment(
                UUID.randomUUID(), UUID.randomUUID(), 1, StockAdjustmentReason.COMPLETED);

        assertThatCode(() -> publisher.publish(adjustment))
                .doesNotThrowAnyException();

        verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq(TOPIC),
                org.mockito.ArgumentMatchers.eq(adjustment.dropId().toString()),
                org.mockito.ArgumentMatchers.anyString());
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }

    @Configuration
    @EnableTransactionManagement
    static class TestConfiguration {

        @Bean
        StockAdjustmentEventPublisher stockAdjustmentEventPublisher(
                KafkaTemplate<String, String> kafkaTemplate,
                ObjectMapper objectMapper
        ) {
            return new StockAdjustmentEventPublisher(kafkaTemplate, objectMapper, TOPIC);
        }
    }
}
