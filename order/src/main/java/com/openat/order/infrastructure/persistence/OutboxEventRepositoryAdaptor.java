package com.openat.order.infrastructure.persistence;

import com.openat.order.domain.model.OutboxEvent;
import com.openat.order.domain.model.OutboxEventStatus;
import com.openat.order.domain.repository.OutboxEventRepository;
import java.util.List;
import java.util.Optional;
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
    public Optional<OutboxEvent> findById(UUID id) {
        return outboxEventJpaRepository.findById(id);
    }

    @Override
    public List<OutboxEvent> findPending(int limit) {
        return outboxEventJpaRepository.findByStatusOrderByCreatedAtAsc(
                OutboxEventStatus.PENDING,
                PageRequest.of(0, limit));
    }

    @Override
    @Transactional
    public long deletePublishedBefore(Instant cutoff) {
        return outboxEventJpaRepository.deletePublishedBefore(
                OutboxEventStatus.PUBLISHED,
                cutoff);
    }
}
