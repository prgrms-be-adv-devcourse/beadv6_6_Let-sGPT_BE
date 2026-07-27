package com.openat.order.infrastructure.kafka.publisher;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.order.domain.model.OutboxEvent;
import com.openat.order.domain.repository.OutboxEventRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxPollingSchedulerTest {

    @Test
    @DisplayName("findPending 결과를 통째로 publishAll에 넘긴다")
    void publishPendingEvents_handsOffWholeBatch() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxEventPublisher publisher = mock(OutboxEventPublisher.class);
        List<OutboxEvent> pending = List.of(outboxEvent(), outboxEvent());
        when(repository.findPending(100)).thenReturn(pending);

        new OutboxPollingScheduler(repository, publisher).publishPendingEvents();

        verify(publisher).publishAll(pending);
    }

    private OutboxEvent outboxEvent() {
        OutboxEvent event = OutboxEvent.create()
                .topic("order.completed.events")
                .payload("{}")
                .build();
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        return event;
    }
}
