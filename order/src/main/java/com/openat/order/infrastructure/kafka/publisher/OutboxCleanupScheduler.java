package com.openat.order.infrastructure.kafka.publisher;

import com.openat.order.domain.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxCleanupScheduler {

    private static final Duration RETENTION_PERIOD = Duration.ofDays(7);

    private final OutboxEventRepository outboxEventRepository;

    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void deleteExpiredPublishedEvents() {
        Instant cutoff = Instant.now().minus(RETENTION_PERIOD);
        long deletedCount = outboxEventRepository.deletePublishedBefore(cutoff);
        if (deletedCount > 0) {
            log.info("Expired published Outbox events deleted. count={}, cutoff={}", deletedCount, cutoff);
        }
    }
}
