package com.openat.order.application.service;

import com.openat.order.domain.model.InboxEvent;
import com.openat.order.domain.model.InboxEventStatus;
import com.openat.order.domain.model.Order;
import com.openat.order.domain.repository.InboxEventRepository;
import com.openat.order.domain.repository.OrderRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InboxEventFailureRecorder {

    private final InboxEventRepository inboxEventRepository;
    private final OrderRepository orderRepository;
    private final OrderHistoryRecorder orderHistoryRecorder;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean record(
            String eventId,
            String eventType,
            String payload,
            UUID orderId,
            RuntimeException exception,
            boolean businessDecisionFailure
    ) {
        InboxEvent inboxEvent = findOrCreate(eventId, eventType, payload);
        if (inboxEvent.getStatus() == InboxEventStatus.PROCESSED) {
            return true;
        }
        inboxEvent.markFailed(exception.getMessage());

        if (businessDecisionFailure && orderId != null) {
            orderRepository.findById(orderId).ifPresent(order -> recordRejectedEvent(order, eventId, exception));
            log.error(
                    "Business event sent to DLQ. financialImpact=true, eventId={}, orderId={}, eventType={}",
                    eventId,
                    orderId,
                    eventType,
                    exception);
        }
        return false;
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

    private void recordRejectedEvent(Order order, String eventId, RuntimeException exception) {
        orderHistoryRecorder.record(
                order,
                order.getStatus(),
                "EVENT_PROCESSING_REJECTED",
                exception.getMessage(),
                eventId);
    }
}
