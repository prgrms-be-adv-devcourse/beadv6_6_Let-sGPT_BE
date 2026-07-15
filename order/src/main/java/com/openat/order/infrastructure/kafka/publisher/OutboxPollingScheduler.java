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
        outboxEventRepository.findPending(BATCH_SIZE).forEach(event -> {
            try {
                outboxEventPublisher.publish(event.getId());
            } catch (RuntimeException exception) {
                log.error("Unexpected Outbox publishing failure; continuing batch. outboxEventId={}",
                        event.getId(), exception);
            }
        });
    }
}
