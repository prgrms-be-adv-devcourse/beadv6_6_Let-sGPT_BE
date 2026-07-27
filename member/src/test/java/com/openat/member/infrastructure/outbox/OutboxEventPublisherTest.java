package com.openat.member.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxEventPublisherTest {

    @Test
    @DisplayName("발행 성공 시 aggregateId를 key로 전송하고 PUBLISHED로 변경한다")
    void publish_whenKafkaSucceeds_marksPublished() {
        OutboxEventJpaRepository repository = mock(OutboxEventJpaRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OutboxEventJpaEntity event = new OutboxEventJpaEntity("WISHLIST", aggregateId,
                "wishlist.changed.events", "{}");
        ReflectionTestUtils.setField(event, "id", eventId);
        when(repository.findById(eventId)).thenReturn(Optional.of(event));
        when(kafkaTemplate.send(event.getTopic(), aggregateId.toString(), event.getPayload()))
                .thenReturn(CompletableFuture.completedFuture(null));

        new OutboxEventPublisher(repository, kafkaTemplate).publish(eventId);

        verify(kafkaTemplate).send(event.getTopic(), aggregateId.toString(), event.getPayload());
        assertThat(event.getStatus()).isEqualTo(OutboxEventJpaEntity.Status.PUBLISHED);
    }

    @Test
    @DisplayName("Kafka 발행 실패 시 PENDING 상태를 유지한다")
    void publish_whenKafkaFails_keepsPending() {
        OutboxEventJpaRepository repository = mock(OutboxEventJpaRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OutboxEventJpaEntity event = new OutboxEventJpaEntity("WISHLIST", aggregateId,
                "wishlist.changed.events", "{}");
        ReflectionTestUtils.setField(event, "id", eventId);
        when(repository.findById(eventId)).thenReturn(Optional.of(event));
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("kafka unavailable"));
        when(kafkaTemplate.send(event.getTopic(), aggregateId.toString(), event.getPayload()))
                .thenReturn(failed);

        new OutboxEventPublisher(repository, kafkaTemplate).publish(eventId);

        assertThat(event.getStatus()).isEqualTo(OutboxEventJpaEntity.Status.PENDING);
    }

    @Test
    @DisplayName("이미 PUBLISHED인 이벤트는 재발행하지 않는다 (스케줄러 조회~발행 사이 중복 처리 방지)")
    void publish_whenAlreadyPublished_doesNothing() {
        OutboxEventJpaRepository repository = mock(OutboxEventJpaRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        UUID eventId = UUID.randomUUID();
        OutboxEventJpaEntity event = new OutboxEventJpaEntity("WISHLIST", UUID.randomUUID(),
                "wishlist.changed.events", "{}");
        event.markPublished();
        ReflectionTestUtils.setField(event, "id", eventId);
        when(repository.findById(eventId)).thenReturn(Optional.of(event));

        new OutboxEventPublisher(repository, kafkaTemplate).publish(eventId);

        verify(kafkaTemplate, never()).send(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("Kafka 대기 중 인터럽트되면 플래그를 복원하고 PENDING을 유지한다")
    void publish_whenInterrupted_restoresInterruptFlag() throws Exception {
        OutboxEventJpaRepository repository = mock(OutboxEventJpaRepository.class);
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
        @SuppressWarnings("unchecked")
        CompletableFuture<SendResult<String, String>> future = mock(CompletableFuture.class);
        UUID aggregateId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        OutboxEventJpaEntity event = new OutboxEventJpaEntity("WISHLIST", aggregateId,
                "wishlist.changed.events", "{}");
        ReflectionTestUtils.setField(event, "id", eventId);
        when(repository.findById(eventId)).thenReturn(Optional.of(event));
        when(kafkaTemplate.send(event.getTopic(), aggregateId.toString(), event.getPayload()))
                .thenReturn(future);
        when(future.get(5, TimeUnit.SECONDS)).thenThrow(new InterruptedException("shutdown"));

        try {
            new OutboxEventPublisher(repository, kafkaTemplate).publish(eventId);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(event.getStatus()).isEqualTo(OutboxEventJpaEntity.Status.PENDING);
        } finally {
            Thread.interrupted();
        }
    }
}
