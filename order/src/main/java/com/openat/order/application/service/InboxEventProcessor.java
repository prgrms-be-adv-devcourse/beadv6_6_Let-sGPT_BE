package com.openat.order.application.service;

import com.openat.order.domain.model.InboxEvent;
import com.openat.order.domain.model.InboxEventStatus;
import com.openat.order.domain.repository.InboxEventRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InboxEventProcessor {

    private final InboxEventRepository inboxEventRepository;

    @Transactional
    public void process(String eventId, String eventType, String payload, Runnable action) {
        InboxEvent inboxEvent = findOrCreate(eventId, eventType, payload);
        if (inboxEvent.getStatus() == InboxEventStatus.PROCESSED) {
            return;
        }

        inboxEvent.retry();
        action.run();
        inboxEvent.markProcessed(Instant.now());
    }

    private InboxEvent findOrCreate(String eventId, String eventType, String payload) {
        return inboxEventRepository.findByEventId(eventId)
                .orElseGet(() -> create(eventId, eventType, payload));
    }

    private InboxEvent create(String eventId, String eventType, String payload) {
        return inboxEventRepository.saveAndFlush(
                InboxEvent.receive()
                        .eventId(eventId)
                        .eventType(eventType)
                        .payload(payload)
                        .build());
    }
}
