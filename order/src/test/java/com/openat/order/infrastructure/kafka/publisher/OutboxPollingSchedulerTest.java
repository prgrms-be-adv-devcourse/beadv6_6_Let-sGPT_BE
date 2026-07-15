package com.openat.order.infrastructure.kafka.publisher;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openat.order.domain.model.OutboxEvent;
import com.openat.order.domain.repository.OutboxEventRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxPollingSchedulerTest {

    @Test
    @DisplayName("한 Outbox 행 발행이 예외여도 다음 행 처리를 계속한다")
    void publishPendingEvents_whenOneEventFails_continuesBatch() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        OutboxEventPublisher publisher = mock(OutboxEventPublisher.class);
        OutboxEvent first = outboxEvent();
        OutboxEvent second = outboxEvent();
        when(repository.findPending(100)).thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("poison event"))
                .when(publisher)
                .publish(first.getId());

        new OutboxPollingScheduler(repository, publisher).publishPendingEvents();

        InOrder order = inOrder(publisher);
        order.verify(publisher).publish(first.getId());
        order.verify(publisher).publish(second.getId());
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
