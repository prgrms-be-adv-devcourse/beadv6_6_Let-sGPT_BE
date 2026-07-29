package com.openat.order.domain.repository;

import com.openat.order.domain.model.InboxEvent;
import java.util.Optional;

public interface InboxEventRepository {

  InboxEvent saveAndFlush(InboxEvent inboxEvent);

  Optional<InboxEvent> findByEventId(String eventId);
}
