package com.openat.order.infrastructure.kafka.publisher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.domain.model.OutboxEvent;
import com.openat.order.domain.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OutboxEventPublisher {

  private final OutboxEventRepository outboxEventRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  // delivery.timeout.ms(10s)보다 커야 한다 — 좁히면 뒤 future가 대기 창 밖으로 밀려 발행됐는데 PENDING으로 남는다
  private final long batchTimeoutSeconds;

  public OutboxEventPublisher(
      OutboxEventRepository outboxEventRepository,
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper,
      MeterRegistry meterRegistry,
      @Value("${order.outbox.batch-timeout-seconds:15}") long batchTimeoutSeconds) {
    this.outboxEventRepository = outboxEventRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.batchTimeoutSeconds = batchTimeoutSeconds;
  }

  public void publishAll(List<OutboxEvent> events) {
    if (events == null || events.isEmpty()) {
      return;
    }

    Map<UUID, CompletableFuture<SendResult<String, String>>> futures = new LinkedHashMap<>();
    for (OutboxEvent event : events) {
      String orderId = extractOrderId(event.getPayload());
      if (orderId == null) {
        outboxEventRepository.markFailed(event.getId());
        meterRegistry.counter("order.outbox.failed").increment();
        log.error(
            "Outbox event payload is invalid; marked FAILED. outboxEventId={}, topic={}",
            event.getId(),
            event.getTopic());
        continue;
      }
      try {
        futures.put(
            event.getId(), kafkaTemplate.send(event.getTopic(), orderId, event.getPayload()));
      } catch (RuntimeException exception) {
        // 버퍼 풀·max.block.ms 초과·직렬화 실패 같은 동기 send 실패는 이 이벤트에만 국한 — PENDING으로 두고 루프를 계속한다
        log.error(
            "Outbox event synchronous send failed; left PENDING for next poll. "
                + "outboxEventId={}, topic={}",
            event.getId(),
            event.getTopic(),
            exception);
      }
    }

    if (futures.isEmpty()) {
      return;
    }

    awaitBatch(futures.values());

    List<UUID> succeeded = new ArrayList<>();
    for (Map.Entry<UUID, CompletableFuture<SendResult<String, String>>> entry :
        futures.entrySet()) {
      CompletableFuture<SendResult<String, String>> future = entry.getValue();
      if (future.isDone() && !future.isCancelled() && !future.isCompletedExceptionally()) {
        succeeded.add(entry.getKey());
      }
    }

    int pendingRemaining = futures.size() - succeeded.size();
    if (pendingRemaining > 0) {
      log.warn(
          "Outbox batch partially delivered; {} event(s) remain PENDING for next poll.",
          pendingRemaining);
    }

    if (succeeded.isEmpty()) {
      return;
    }

    int updated = outboxEventRepository.markPublishedAll(succeeded, Instant.now());
    meterRegistry.counter("order.outbox.published").increment(succeeded.size());
    if (updated != succeeded.size()) {
      log.warn(
          "Outbox publish drift detected. successCount={}, updatedRows={}",
          succeeded.size(),
          updated);
    }
    log.info("Outbox batch published. successCount={}, updatedRows={}", succeeded.size(), updated);
  }

  private void awaitBatch(
      java.util.Collection<CompletableFuture<SendResult<String, String>>> futures) {
    CompletableFuture<Void> all =
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    try {
      all.get(batchTimeoutSeconds, TimeUnit.SECONDS);
    } catch (TimeoutException exception) {
      log.warn(
          "Outbox batch send timed out after {}s; incomplete events remain PENDING.",
          batchTimeoutSeconds);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      log.warn("Outbox batch send interrupted; incomplete events remain PENDING.");
    } catch (Exception exception) {
      log.warn("Outbox batch send had failures; failed events remain PENDING.", exception);
    }
  }

  private String extractOrderId(String payload) {
    try {
      JsonNode root = objectMapper.readTree(payload);
      return UUID.fromString(root.required("orderId").asText()).toString();
    } catch (Exception exception) {
      return null;
    }
  }
}
