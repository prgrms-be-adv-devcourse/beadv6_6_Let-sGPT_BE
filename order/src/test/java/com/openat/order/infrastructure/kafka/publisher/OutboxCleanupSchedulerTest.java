package com.openat.order.infrastructure.kafka.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.openat.order.domain.repository.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OutboxCleanupSchedulerTest {

    @Test
    @DisplayName("7일보다 오래된 PUBLISHED Outbox 행의 삭제를 요청한다")
    void deleteExpiredPublishedEvents_usesSevenDayCutoff() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        Instant before = Instant.now().minus(Duration.ofDays(7));

        new OutboxCleanupScheduler(repository).deleteExpiredPublishedEvents();

        Instant after = Instant.now().minus(Duration.ofDays(7));
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(repository).deletePublishedBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isBetween(before, after);
    }
}
