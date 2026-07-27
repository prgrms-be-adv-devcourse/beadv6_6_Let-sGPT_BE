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
            // Single-replica assumption: findPending has no FOR UPDATE SKIP LOCKED / distributed
            // lock, so multiple replicas would poll the same PENDING rows and duplicate-send.
            // Running >1 replica requires SKIP LOCKED or a distributed lock here.
            outboxEventPublisher.publishAll(outboxEventRepository.findPending(BATCH_SIZE));
        } catch (RuntimeException exception) {
            log.error("Unexpected Outbox batch publishing failure.", exception);
        }
    }
}
