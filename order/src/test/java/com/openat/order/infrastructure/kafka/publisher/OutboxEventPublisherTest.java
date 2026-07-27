package com.openat.order.infrastructure.kafka.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.domain.model.OutboxEvent;
import com.openat.order.domain.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxEventPublisherTest {

  private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

  @SuppressWarnings("unchecked")
  private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private final OutboxEventPublisher publisher =
      new OutboxEventPublisher(repository, kafkaTemplate, new ObjectMapper(), meterRegistry, 15L);

  @Test
  @DisplayName("배치 전건 성공 시 모든 id를 한 번에 PUBLISHED 처리하고 메트릭은 건수만큼 증가한다")
  void publishAll_whenAllSucceed_marksAllPublished() {
    OutboxEvent first = pendingEvent(UUID.randomUUID());
    OutboxEvent second = pendingEvent(UUID.randomUUID());
    stubSend(first, completed());
    stubSend(second, completed());
    when(repository.markPublishedAll(anyList(), any(Instant.class))).thenReturn(2);

    publisher.publishAll(List.of(first, second));

    ArgumentCaptor<List<UUID>> ids = idsCaptor();
    verify(repository).markPublishedAll(ids.capture(), any(Instant.class));
    assertThat(ids.getValue()).containsExactlyInAnyOrder(first.getId(), second.getId());
    assertThat(meterRegistry.counter("order.outbox.published").count()).isEqualTo(2.0);
    verify(repository, never()).markFailed(any(UUID.class));
  }

  @Test
  @DisplayName("일부 실패 시 성공한 건만 PUBLISHED로 올리고 실패 건은 PENDING으로 남긴다")
  void publishAll_whenPartialFailure_marksOnlySucceeded() {
    OutboxEvent ok = pendingEvent(UUID.randomUUID());
    OutboxEvent bad = pendingEvent(UUID.randomUUID());
    stubSend(ok, completed());
    stubSend(bad, failed());
    when(repository.markPublishedAll(anyList(), any(Instant.class))).thenReturn(1);

    publisher.publishAll(List.of(ok, bad));

    ArgumentCaptor<List<UUID>> ids = idsCaptor();
    verify(repository).markPublishedAll(ids.capture(), any(Instant.class));
    assertThat(ids.getValue()).containsExactly(ok.getId());
    assertThat(meterRegistry.counter("order.outbox.published").count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("배치 전체가 타임아웃되면 아무것도 PUBLISHED로 올리지 않고 PENDING을 유지한다")
  void publishAll_whenBatchTimesOut_marksNothingPublished() {
    OutboxEventPublisher immediateTimeoutPublisher =
        new OutboxEventPublisher(repository, kafkaTemplate, new ObjectMapper(), meterRegistry, 0L);
    OutboxEvent first = pendingEvent(UUID.randomUUID());
    OutboxEvent second = pendingEvent(UUID.randomUUID());
    stubSend(first, new CompletableFuture<>()); // never completes
    stubSend(second, new CompletableFuture<>());

    immediateTimeoutPublisher.publishAll(List.of(first, second));

    verify(repository, never()).markPublishedAll(anyList(), any(Instant.class));
    assertThat(meterRegistry.counter("order.outbox.published").count()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("동기 send() 예외는 해당 건만 PENDING으로 남기고 나머지 성공 건은 PUBLISHED 처리한다")
  void publishAll_whenSendThrowsSynchronously_isolatesFailedEvent() {
    OutboxEvent throwing = pendingEvent(UUID.randomUUID());
    OutboxEvent ok = pendingEvent(UUID.randomUUID());
    when(kafkaTemplate.send(
            eq(throwing.getTopic()), eq(orderIdOf(throwing)), eq(throwing.getPayload())))
        .thenThrow(new org.springframework.kafka.KafkaException("buffer full"));
    stubSend(ok, completed());
    when(repository.markPublishedAll(anyList(), any(Instant.class))).thenReturn(1);

    publisher.publishAll(List.of(throwing, ok));

    ArgumentCaptor<List<UUID>> ids = idsCaptor();
    verify(repository).markPublishedAll(ids.capture(), any(Instant.class));
    assertThat(ids.getValue()).containsExactly(ok.getId());
    verify(repository, never()).markFailed(throwing.getId());
    assertThat(meterRegistry.counter("order.outbox.published").count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("poison payload를 FAILED로 마킹할 때 order.outbox.failed 메트릭이 증가한다")
  void publishAll_whenPayloadMalformed_incrementsFailedMetric() {
    OutboxEvent poison = event(UUID.randomUUID(), "{\"amount\":10000}");

    publisher.publishAll(List.of(poison));

    assertThat(meterRegistry.counter("order.outbox.failed").count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("orderId가 없는 poison payload는 전송 전에 분리되어 FAILED로 마킹된다")
  void publishAll_whenPayloadMalformed_marksFailedWithoutSending() {
    UUID id = UUID.randomUUID();
    OutboxEvent poison = event(id, "{\"amount\":10000}");

    publisher.publishAll(List.of(poison));

    verify(repository).markFailed(id);
    verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    verify(repository, never()).markPublishedAll(anyList(), any(Instant.class));
  }

  @Test
  @DisplayName("이미 PUBLISHED된 행에 대한 벌크 UPDATE는 0행만 갱신되고(드리프트) 예외 없이 로깅된다")
  void publishAll_whenRowsAlreadyPublished_updateAffectsZeroRows() {
    OutboxEvent event = pendingEvent(UUID.randomUUID());
    stubSend(event, completed());
    when(repository.markPublishedAll(anyList(), any(Instant.class))).thenReturn(0);

    publisher.publishAll(List.of(event));

    verify(repository).markPublishedAll(anyList(), any(Instant.class));
    assertThat(meterRegistry.counter("order.outbox.published").count()).isEqualTo(1.0);
  }

  private OutboxEvent pendingEvent(UUID orderId) {
    return event(UUID.randomUUID(), "{\"orderId\":\"" + orderId + "\"}");
  }

  private OutboxEvent event(UUID id, String payload) {
    OutboxEvent event =
        OutboxEvent.create().topic("order.completed.events").payload(payload).build();
    ReflectionTestUtils.setField(event, "id", id);
    return event;
  }

  private void stubSend(OutboxEvent event, CompletableFuture<SendResult<String, String>> future) {
    String orderId = orderIdOf(event);
    when(kafkaTemplate.send(eq(event.getTopic()), eq(orderId), eq(event.getPayload())))
        .thenReturn(future);
  }

  private String orderIdOf(OutboxEvent event) {
    try {
      return new ObjectMapper().readTree(event.getPayload()).get("orderId").asText();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private CompletableFuture<SendResult<String, String>> completed() {
    return CompletableFuture.completedFuture(null);
  }

  private CompletableFuture<SendResult<String, String>> failed() {
    CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
    future.completeExceptionally(new RuntimeException("kafka unavailable"));
    return future;
  }

  @SuppressWarnings("unchecked")
  private ArgumentCaptor<List<UUID>> idsCaptor() {
    return ArgumentCaptor.forClass(List.class);
  }
}
