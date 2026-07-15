package com.openat.order.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.domain.model.InboxEvent;
import com.openat.order.domain.model.InboxEventStatus;
import com.openat.order.domain.repository.InboxEventRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InboxEventProcessorTest {

    @Test
    @DisplayName("신규 이벤트 처리 성공 시 Inbox를 PROCESSED로 마킹한다")
    void process_newEvent_marksProcessed() {
        InboxEventRepository repository = mock(InboxEventRepository.class);
        InboxEvent event = inboxEvent("payment.completed:payment:order");
        when(repository.findByEventId(event.getEventId())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(org.mockito.ArgumentMatchers.any())).thenReturn(event);
        Runnable action = mock(Runnable.class);

        new InboxEventProcessor(repository).process(
                event.getEventId(), "payment.completed", "{}", action);

        verify(action).run();
        assertThat(event.getStatus()).isEqualTo(InboxEventStatus.PROCESSED);
        assertThat(event.getProcessedAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 PROCESSED인 중복 이벤트는 즉시 스킵한다")
    void process_processedEvent_skipsAction() {
        InboxEventRepository repository = mock(InboxEventRepository.class);
        InboxEvent event = inboxEvent("payment.completed:payment:order");
        event.markProcessed(Instant.now());
        when(repository.findByEventId(event.getEventId())).thenReturn(Optional.of(event));
        Runnable action = mock(Runnable.class);

        new InboxEventProcessor(repository).process(
                event.getEventId(), "payment.completed", "{}", action);

        verify(action, never()).run();
    }

    @Test
    @DisplayName("FAILED 이벤트는 기존 Inbox 행을 재사용해 다시 처리한다")
    void process_failedEvent_reusesRow() {
        InboxEventRepository repository = mock(InboxEventRepository.class);
        InboxEvent event = inboxEvent("payment.completed:payment:order");
        event.markFailed("temporary failure");
        when(repository.findByEventId(event.getEventId())).thenReturn(Optional.of(event));
        Runnable action = mock(Runnable.class);

        new InboxEventProcessor(repository).process(
                event.getEventId(), "payment.completed", "{}", action);

        verify(action).run();
        verify(repository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
        assertThat(event.getStatus()).isEqualTo(InboxEventStatus.PROCESSED);
    }

    private InboxEvent inboxEvent(String eventId) {
        return InboxEvent.receive()
                .eventId(eventId)
                .eventType("payment.completed")
                .payload("{}")
                .build();
    }
}
