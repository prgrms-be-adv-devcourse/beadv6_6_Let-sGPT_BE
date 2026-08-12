package com.openat.product.infrastructure.kafka;

import com.openat.product.domain.model.ProductOutboxEvent;
import com.openat.product.domain.repository.ProductOutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ProductOutboxRelay {

  private final ProductOutboxEventRepository outboxEventRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ProductOutboxMetrics metrics;
  private final int batchSize;
  private final Duration claimTimeout;
  private final Duration sendTimeout;

  public ProductOutboxRelay(
      ProductOutboxEventRepository outboxEventRepository,
      KafkaTemplate<String, String> kafkaTemplate,
      ProductOutboxMetrics metrics,
      @Value("${product.outbox.relay.batch-size:100}") int batchSize,
      @Value("${product.outbox.relay.claim-timeout:30s}") Duration claimTimeout,
      @Value("${product.outbox.relay.send-timeout:10s}") Duration sendTimeout) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException("product outbox relay batchSize must be positive");
    }
    if (claimTimeout.isZero() || claimTimeout.isNegative()) {
      throw new IllegalArgumentException("product outbox relay claimTimeout must be positive");
    }
    if (sendTimeout.isZero() || sendTimeout.isNegative()) {
      throw new IllegalArgumentException("product outbox relay sendTimeout must be positive");
    }
    this.outboxEventRepository = outboxEventRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.metrics = metrics;
    this.batchSize = batchSize;
    this.claimTimeout = claimTimeout;
    this.sendTimeout = sendTimeout;
  }

  @Scheduled(fixedDelayString = "${product.outbox.relay.fixed-delay-ms:3000}")
  public void relay() {
    Instant claimedAt = Instant.now();
    List<ProductOutboxEvent> events =
        outboxEventRepository.claimPending(
            batchSize, claimedAt, claimedAt.minus(claimTimeout));
    if (events.isEmpty()) {
      return;
    }

    List<PendingSend> sends = new ArrayList<>(events.size());
    for (ProductOutboxEvent event : events) {
      sends.add(send(event));
    }
    awaitAcknowledgements(sends);

    List<UUID> publishedIds = new ArrayList<>();
    List<UUID> failedIds = new ArrayList<>();
    for (PendingSend send : sends) {
      if (completedSuccessfully(send.future())) {
        publishedIds.add(send.eventId());
      } else {
        failedIds.add(send.eventId());
      }
    }

    int published = outboxEventRepository.markPublished(publishedIds, Instant.now());
    int released = outboxEventRepository.release(failedIds);
    metrics.recordPublished(published);
    metrics.recordFailed(released);
    if (!failedIds.isEmpty()) {
      log.warn(
          "Product outbox relay released failed events for retry. claimed={}, failed={}",
          events.size(),
          failedIds.size());
    }
  }

  private PendingSend send(ProductOutboxEvent event) {
    try {
      CompletableFuture<SendResult<String, String>> future =
          kafkaTemplate.send(
              event.getTopic(), event.getAggregateId().toString(), event.getPayload());
      return new PendingSend(event.getId(), future);
    } catch (RuntimeException exception) {
      log.warn(
          "Product outbox send failed before Kafka acknowledgement. eventId={}",
          event.getId(),
          exception);
      return new PendingSend(event.getId(), CompletableFuture.failedFuture(exception));
    }
  }

  private void awaitAcknowledgements(List<PendingSend> sends) {
    CompletableFuture<?>[] futures =
        sends.stream().map(PendingSend::future).toArray(CompletableFuture[]::new);
    try {
      CompletableFuture.allOf(futures).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      log.warn("Product outbox acknowledgement wait was interrupted.", exception);
    } catch (Exception exception) {
      log.warn("Product outbox acknowledgement wait did not complete successfully.", exception);
    }
  }

  private boolean completedSuccessfully(CompletableFuture<?> future) {
    return future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled();
  }

  private record PendingSend(
      UUID eventId, CompletableFuture<SendResult<String, String>> future) {}
}
