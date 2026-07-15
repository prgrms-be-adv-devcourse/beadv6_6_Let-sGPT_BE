package com.openat.order.infrastructure.persistence;

import com.openat.order.domain.model.OutboxEvent;
import com.openat.order.domain.model.OutboxEventStatus;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxEventStatus status, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from OutboxEvent event "
            + "where event.status = :status and event.publishedAt < :cutoff")
    int deletePublishedBefore(
            @Param("status") OutboxEventStatus status,
            @Param("cutoff") Instant cutoff);
}
