package com.openat.order.infrastructure.persistence;

import com.openat.order.domain.model.InboxEvent;
import com.openat.order.domain.repository.InboxEventRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class InboxEventRepositoryAdaptor implements InboxEventRepository {

  private final InboxEventJpaRepository inboxEventJpaRepository;

  @Override
  public InboxEvent saveAndFlush(InboxEvent inboxEvent) {
    return inboxEventJpaRepository.saveAndFlush(inboxEvent);
  }

  @Override
  public Optional<InboxEvent> findByEventId(String eventId) {
    return inboxEventJpaRepository.findByEventId(eventId);
  }
}
