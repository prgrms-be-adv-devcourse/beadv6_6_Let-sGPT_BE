package com.openat.order.infrastructure.persistence;

import com.openat.order.domain.model.InboxEvent;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxEventJpaRepository extends JpaRepository<InboxEvent, UUID> {

    Optional<InboxEvent> findByEventId(String eventId);
}
