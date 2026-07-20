package com.openat.order.infrastructure.kafka.publisher;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.domain.model.OutboxEvent;
import com.openat.order.domain.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxEventPublisher {

  private final OutboxEventRepository outboxEventRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void publish(UUID outboxEventId) {
    OutboxEvent event = outboxEventRepository.findById(outboxEventId).orElse(null);
    if (event == null
        || event.getStatus() != com.openat.order.domain.model.OutboxEventStatus.PENDING) {
      return;
    }

    String orderId;
    try {
      orderId = extractOrderId(event.getPayload());
      kafkaTemplate.send(event.getTopic(), orderId, event.getPayload()).get(5, TimeUnit.SECONDS);
      event.markPublished(Instant.now());
      meterRegistry.counter("order.outbox.published").increment();
      log.info(
          "Outbox event published. outboxEventId={}, orderId={}, topic={}",
          event.getId(),
          orderId,
          event.getTopic());
    } catch (InvalidOutboxPayloadException exception) {
      event.markFailed();
      log.error(
          "Outbox event payload is invalid; marked FAILED. outboxEventId={}, topic={}",
          event.getId(),
          event.getTopic(),
          exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      log.warn(
          "Outbox event publishing interrupted; remains PENDING. outboxEventId={}, topic={}",
          event.getId(),
          event.getTopic(),
          exception);
    } catch (Exception exception) {
      log.error(
          "Outbox event publish failed; remains PENDING. outboxEventId={}, topic={}",
          event.getId(),
          event.getTopic(),
          exception);
    }
  }

  private String extractOrderId(String payload) {
    try {
      JsonNode root = objectMapper.readTree(payload);
      return UUID.fromString(root.required("orderId").asText()).toString();
    } catch (Exception exception) {
      throw new InvalidOutboxPayloadException("Outbox payload에 orderId가 없습니다.", exception);
    }
  }

  private static class InvalidOutboxPayloadException extends RuntimeException {

    private InvalidOutboxPayloadException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
