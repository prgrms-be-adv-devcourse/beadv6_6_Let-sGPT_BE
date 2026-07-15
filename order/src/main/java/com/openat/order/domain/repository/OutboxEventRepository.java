package com.openat.order.domain.repository;

import com.openat.order.domain.model.OutboxEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface OutboxEventRepository {

    OutboxEvent save(OutboxEvent outboxEvent);

    Optional<OutboxEvent> findById(UUID id);

    List<OutboxEvent> findPending(int limit);

    long deletePublishedBefore(Instant cutoff);
}
