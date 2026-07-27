package com.openat.payment.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxPollingSchedulerTest {

    private static final String TOPIC = "payment.events";

    @Test
    @DisplayName("ack 받은 이벤트만 PUBLISHED로 확정하고, 실패한 이벤트는 PENDING으로 남긴다")
    void publishPending_confirmsOnlyAckedEvents() {
        OutboxEventJpaRepository repository = mock(OutboxEventJpaRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        OutboxEventJpaEntity acked = event("{\"no\":1}");
        OutboxEventJpaEntity failed = event("{\"no\":2}");
        when(repository.findByStatusOrderByCreatedAtAsc(
                eq(OutboxEventJpaEntity.Status.PENDING), any(Pageable.class)))
                .thenReturn(List.of(acked, failed));
        when(kafkaTemplate.send(TOPIC, acked.getAggregateId().toString(), acked.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send(TOPIC, failed.getAggregateId().toString(), failed.getPayload()))
                .thenReturn(failedFuture());
        when(repository.markPublished(anyCollection(),
                eq(OutboxEventJpaEntity.Status.PUBLISHED),
                eq(OutboxEventJpaEntity.Status.PENDING),
                any(LocalDateTime.class)))
                .thenReturn(1);

        new OutboxPollingScheduler(repository, kafkaTemplate, meterRegistry).publishPending();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(repository).markPublished(ids.capture(),
                eq(OutboxEventJpaEntity.Status.PUBLISHED),
                eq(OutboxEventJpaEntity.Status.PENDING),
                any(LocalDateTime.class));
        assertThat(ids.getValue()).containsExactly(acked.getId());
        assertThat(meterRegistry.counter("payment.outbox.published").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ack를 하나도 못 받으면 확정 UPDATE를 아예 호출하지 않는다")
    void publishPending_whenNothingAcked_skipsUpdate() {
        OutboxEventJpaRepository repository = mock(OutboxEventJpaRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        OutboxEventJpaEntity failed = event("{\"no\":1}");
        when(repository.findByStatusOrderByCreatedAtAsc(
                eq(OutboxEventJpaEntity.Status.PENDING), any(Pageable.class)))
                .thenReturn(List.of(failed));
        when(kafkaTemplate.send(TOPIC, failed.getAggregateId().toString(), failed.getPayload()))
                .thenReturn(failedFuture());

        new OutboxPollingScheduler(repository, kafkaTemplate, meterRegistry).publishPending();

        verify(repository, never()).markPublished(anyCollection(), any(), any(), any());
        assertThat(meterRegistry.counter("payment.outbox.published").count()).isEqualTo(0.0);
    }

    private OutboxEventJpaEntity event(String payload) {
        return new OutboxEventJpaEntity(UUID.randomUUID(), "PAYMENT", UUID.randomUUID(), TOPIC, payload);
    }

    private CompletableFuture<SendResult<String, String>> failedFuture() {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("kafka unavailable"));
        return future;
    }
}
