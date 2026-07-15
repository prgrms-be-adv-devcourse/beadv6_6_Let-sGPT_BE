package com.openat.order.infrastructure.kafka.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.domain.model.OutboxEvent;
import com.openat.order.domain.model.OutboxEventStatus;
import com.openat.order.domain.repository.OutboxEventRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxEventPublisherTest {

    @Test
    @DisplayName("Outbox 발행 성공 시 orderId를 key로 전송하고 PUBLISHED로 변경한다")
    void publish_whenKafkaSucceeds_marksPublished() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        UUID orderId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.create()
                .topic("order.completed.events")
                .payload("{\"orderId\":\"" + orderId + "\"}")
                .build();
        UUID eventId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(event, "id", eventId);
        when(repository.findById(eventId)).thenReturn(Optional.of(event));
        when(kafkaTemplate.send(event.getTopic(), orderId.toString(), event.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        new OutboxEventPublisher(repository, kafkaTemplate, new ObjectMapper()).publish(eventId);

        verify(kafkaTemplate).send(event.getTopic(), orderId.toString(), event.getPayload());
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Kafka 발행 실패 시 Outbox 상태를 PENDING으로 유지한다")
    void publish_whenKafkaFails_keepsPending() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        UUID orderId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.create()
                .topic("order.completed.events")
                .payload("{\"orderId\":\"" + orderId + "\"}")
                .build();
        UUID eventId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(event, "id", eventId);
        when(repository.findById(eventId)).thenReturn(Optional.of(event));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("kafka unavailable"));
        when(kafkaTemplate.send(event.getTopic(), orderId.toString(), event.getPayload())).thenReturn(failed);

        new OutboxEventPublisher(repository, kafkaTemplate, new ObjectMapper()).publish(eventId);

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(event.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("orderId가 없는 poison payload는 FAILED로 마킹한다")
    void publish_whenPayloadHasNoOrderId_marksFailed() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        OutboxEvent event = OutboxEvent.create()
                .topic("order.completed.events")
                .payload("{\"amount\":10000}")
                .build();
        UUID eventId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(event, "id", eventId);
        when(repository.findById(eventId)).thenReturn(Optional.of(event));

        new OutboxEventPublisher(repository, kafkaTemplate, new ObjectMapper()).publish(eventId);

        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        verify(kafkaTemplate, never()).send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Kafka 대기 중 인터럽트되면 플래그를 복원하고 PENDING을 유지한다")
    void publish_whenInterrupted_restoresInterruptFlag() throws Exception {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        UUID orderId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.create()
                .topic("order.completed.events")
                .payload("{\"orderId\":\"" + orderId + "\"}")
                .build();
        UUID eventId = UUID.randomUUID();
        org.springframework.test.util.ReflectionTestUtils.setField(event, "id", eventId);
        when(repository.findById(eventId)).thenReturn(Optional.of(event));
        when(kafkaTemplate.send(event.getTopic(), orderId.toString(), event.getPayload())).thenReturn(future);
        when(future.get(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException("shutdown"));

        try {
            new OutboxEventPublisher(repository, kafkaTemplate, new ObjectMapper()).publish(eventId);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        } finally {
            Thread.interrupted();
        }
    }
}
