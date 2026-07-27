package com.openat.member.infrastructure.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

class OutboxPollingSchedulerTest {

    @Test
    @DisplayName("한 Outbox 행 발행이 예외여도 다음 행 처리를 계속한다")
    void publishPending_whenOneEventFails_continuesBatch() {
        OutboxEventJpaRepository repository = mock(OutboxEventJpaRepository.class);
        OutboxEventPublisher publisher = mock(OutboxEventPublisher.class);
        OutboxEventJpaEntity first = outboxEvent();
        OutboxEventJpaEntity second = outboxEvent();
        when(repository.findByStatusOrderByCreatedAtAsc(
                eq(OutboxEventJpaEntity.Status.PENDING), any(Pageable.class)))
                .thenReturn(List.of(first, second));
        doThrow(new IllegalStateException("poison event"))
                .when(publisher)
                .publish(first.getId());

        new OutboxPollingScheduler(repository, publisher).publishPending();

        InOrder order = inOrder(publisher);
        order.verify(publisher).publish(first.getId());
        order.verify(publisher).publish(second.getId());
    }

    private OutboxEventJpaEntity outboxEvent() {
        OutboxEventJpaEntity event = new OutboxEventJpaEntity(
                "WISHLIST", UUID.randomUUID(), "wishlist.changed.events", "{}");
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        return event;
    }
}
