package com.openat.order.infrastructure.kafka.publisher;

import com.openat.order.domain.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPollingScheduler {

  private static final int BATCH_SIZE = 100;

  private final OutboxEventRepository outboxEventRepository;
  private final OutboxEventPublisher outboxEventPublisher;

  @Scheduled(fixedDelay = 3_000)
  public void publishPendingEvents() {
    try {
      // 단일 레플리카 전제 — findPending에 SKIP LOCKED·분산 락이 없어 레플리카를 늘리면 중복 발행된다
      outboxEventPublisher.publishAll(outboxEventRepository.findPending(BATCH_SIZE));
    } catch (RuntimeException exception) {
      log.error("Unexpected Outbox batch publishing failure.", exception);
    }
  }
}
