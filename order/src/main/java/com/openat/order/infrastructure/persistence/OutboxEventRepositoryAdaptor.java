package com.openat.order.infrastructure.persistence;

import com.openat.order.domain.model.OutboxEvent;
import com.openat.order.domain.model.OutboxEventStatus;
import com.openat.order.domain.repository.OutboxEventRepository;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class OutboxEventRepositoryAdaptor implements OutboxEventRepository {

    private final OutboxEventJpaRepository outboxEventJpaRepository;

    @Override
    public OutboxEvent save(OutboxEvent outboxEvent) {
        return outboxEventJpaRepository.save(outboxEvent);
    }

    @Override
    public List<OutboxEvent> findPending(int limit) {
        return outboxEventJpaRepository.findByStatusOrderByCreatedAtAsc(
                OutboxEventStatus.PENDING,
                PageRequest.of(0, limit));
    }

    @Override
    @Transactional
    public int markPublishedAll(List<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return 0;
        }
        return outboxEventJpaRepository.markPublishedAll(
                ids,
                now,
                OutboxEventStatus.PUBLISHED,
                OutboxEventStatus.PENDING);
    }

    @Override
    @Transactional
    public int markFailed(UUID id) {
        return outboxEventJpaRepository.markFailed(
                id,
                OutboxEventStatus.FAILED,
                OutboxEventStatus.PENDING);
    }

    @Override
    @Transactional
    public long deletePublishedBefore(Instant cutoff) {
        return outboxEventJpaRepository.deletePublishedBefore(
                OutboxEventStatus.PUBLISHED,
                cutoff);
    }
}
