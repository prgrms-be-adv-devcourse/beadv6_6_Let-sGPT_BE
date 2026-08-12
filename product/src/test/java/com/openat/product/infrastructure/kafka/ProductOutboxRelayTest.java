package com.openat.product.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.openat.product.domain.model.ProductOutboxEvent;
import com.openat.product.domain.repository.ProductOutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("상품 outbox relay")
class ProductOutboxRelayTest {

  @Mock private ProductOutboxEventRepository outboxEventRepository;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;
  @Mock private ProductOutboxMetrics metrics;

  @Test
  @DisplayName("Kafka 확인 응답을 받은 이벤트만 PUBLISHED로 전환한다")
  void relay_acknowledgedEvent_marksPublished() {
    ProductOutboxEvent event = event(1L);
    given(outboxEventRepository.claimPending(eq(100), any(Instant.class), any(Instant.class)))
        .willReturn(List.of(event));
    given(
            kafkaTemplate.send(
                event.getTopic(), event.getAggregateId().toString(), event.getPayload()))
        .willReturn(completedSend());
    given(outboxEventRepository.markPublished(eq(List.of(event.getId())), any(Instant.class)))
        .willReturn(1);

    relay(Duration.ofSeconds(10)).relay();

    then(outboxEventRepository)
        .should()
        .markPublished(eq(List.of(event.getId())), any(Instant.class));
    then(outboxEventRepository).should().release(List.of());
    then(metrics).should().recordPublished(1);
    then(metrics).should().recordFailed(0);
  }

  @Test
  @DisplayName("실패한 이벤트는 PENDING으로 되돌려 다음 polling에서 재시도한다")
  void relay_failedEvent_releasesForRetry() {
    ProductOutboxEvent event = event(2L);
    CompletableFuture<SendResult<String, String>> failedSend = new CompletableFuture<>();
    failedSend.completeExceptionally(new IllegalStateException("kafka unavailable"));
    given(outboxEventRepository.claimPending(eq(100), any(Instant.class), any(Instant.class)))
        .willReturn(List.of(event));
    given(
            kafkaTemplate.send(
                event.getTopic(), event.getAggregateId().toString(), event.getPayload()))
        .willReturn(failedSend);
    given(outboxEventRepository.release(List.of(event.getId()))).willReturn(1);

    relay(Duration.ofSeconds(10)).relay();

    then(outboxEventRepository).should().markPublished(eq(List.of()), any(Instant.class));
    then(outboxEventRepository).should().release(List.of(event.getId()));
    then(metrics).should().recordPublished(0);
    then(metrics).should().recordFailed(1);
  }

  @Test
  @DisplayName("확인 응답 timeout은 PENDING으로 되돌려 at-least-once 재시도한다")
  void relay_ackTimeout_releasesForAtLeastOnceRetry() {
    ProductOutboxEvent event = event(3L);
    CompletableFuture<SendResult<String, String>> unfinishedSend = new CompletableFuture<>();
    given(outboxEventRepository.claimPending(eq(100), any(Instant.class), any(Instant.class)))
        .willReturn(List.of(event));
    given(
            kafkaTemplate.send(
                event.getTopic(), event.getAggregateId().toString(), event.getPayload()))
        .willReturn(unfinishedSend);
    given(outboxEventRepository.release(List.of(event.getId()))).willReturn(1);

    relay(Duration.ofMillis(1)).relay();

    then(outboxEventRepository).should().release(List.of(event.getId()));
    then(metrics).should().recordFailed(1);
  }

  @Test
  @DisplayName("ack 대기 interrupt를 복구하고 미완료 이벤트를 재시도 상태로 돌린다")
  void relay_interrupted_restoresInterruptAndReleases() {
    ProductOutboxEvent event = event(4L);
    CompletableFuture<SendResult<String, String>> unfinishedSend = new CompletableFuture<>();
    given(outboxEventRepository.claimPending(eq(100), any(Instant.class), any(Instant.class)))
        .willReturn(List.of(event));
    given(
            kafkaTemplate.send(
                event.getTopic(), event.getAggregateId().toString(), event.getPayload()))
        .willReturn(unfinishedSend);
    given(outboxEventRepository.release(List.of(event.getId()))).willReturn(1);

    try {
      Thread.currentThread().interrupt();

      relay(Duration.ofSeconds(10)).relay();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      then(outboxEventRepository).should().release(List.of(event.getId()));
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("relay polling 설정은 모두 양수여야 한다")
  void constructor_nonPositiveConfig_rejects() {
    assertThatThrownBy(
            () ->
                new ProductOutboxRelay(
                    outboxEventRepository,
                    kafkaTemplate,
                    metrics,
                    0,
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ProductOutboxRelay(
                    outboxEventRepository,
                    kafkaTemplate,
                    metrics,
                    100,
                    Duration.ZERO,
                    Duration.ofSeconds(10)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new ProductOutboxRelay(
                    outboxEventRepository,
                    kafkaTemplate,
                    metrics,
                    100,
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private ProductOutboxRelay relay(Duration sendTimeout) {
    return new ProductOutboxRelay(
        outboxEventRepository,
        kafkaTemplate,
        metrics,
        100,
        Duration.ofSeconds(30),
        sendTimeout);
  }

  private ProductOutboxEvent event(long aggregateSequence) {
    ProductOutboxEvent event =
        ProductOutboxEvent.record()
            .aggregateId(UUID.randomUUID())
            .aggregateSequence(aggregateSequence)
            .topic("product.updated.events")
            .payload("{\"id\":\"00000000-0000-0000-0000-000000000000\"}")
            .build();
    ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
    return event;
  }

  private CompletableFuture<SendResult<String, String>> completedSend() {
    return CompletableFuture.completedFuture(null);
  }
}
