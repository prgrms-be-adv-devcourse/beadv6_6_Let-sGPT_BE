package com.openat.order.domain.repository;

import com.openat.order.domain.model.OutboxEvent;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

public interface OutboxEventRepository {

    OutboxEvent save(OutboxEvent outboxEvent);

    List<OutboxEvent> findPending(int limit);

    int markPublishedAll(List<UUID> ids, Instant now);

    int markFailed(UUID id);

    long deletePublishedBefore(Instant cutoff);
}
