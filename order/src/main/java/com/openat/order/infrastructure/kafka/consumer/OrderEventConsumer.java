package com.openat.order.infrastructure.kafka.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.PaymentCompletedCommand;
import com.openat.order.application.dto.PaymentFailedCommand;
import com.openat.order.application.dto.RefundCompletedCommand;
import com.openat.order.application.dto.RefundFailedCommand;
import com.openat.order.application.service.InboxEventFailureRecorder;
import com.openat.order.application.service.InboxEventProcessor;
import com.openat.order.application.service.OrderEventService;
import com.openat.order.infrastructure.kafka.event.PaymentCompleteEvent;
import com.openat.order.infrastructure.kafka.event.PaymentFailedEvent;
import com.openat.order.infrastructure.kafka.event.RefundCompletedEvent;
import com.openat.order.infrastructure.kafka.event.RefundFailedEvent;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private final ObjectMapper objectMapper;
    private final OrderEventService orderEventService;
    private final InboxEventProcessor inboxEventProcessor;
    private final InboxEventFailureRecorder inboxEventFailureRecorder;

    @KafkaListener(topics = "${order.kafka.topic.payment-complete}", groupId = "${spring.kafka.consumer.group-id}")
    public void onPaymentCompleted(ConsumerRecord<String, String> record) {
        handle(
                record,
                "payment.completed",
                PaymentCompleteEvent.class,
                event -> derivedEventId("payment.completed", event.paymentId(), event.orderId()),
                PaymentCompleteEvent::orderId,
                event -> orderEventService.handlePaymentCompleted(
                        new PaymentCompletedCommand(event.orderId(), event.paymentId(), event.amount())));
    }

    @KafkaListener(topics = "${order.kafka.topic.payment-failed}", groupId = "${spring.kafka.consumer.group-id}")
    public void onPaymentFailed(ConsumerRecord<String, String> record) {
        handle(
                record,
                "payment.failed",
                PaymentFailedEvent.class,
                event -> derivedEventId("payment.failed", event.paymentId(), event.orderId()),
                PaymentFailedEvent::orderId,
                event -> orderEventService.handlePaymentFailed(
                        new PaymentFailedCommand(event.orderId(), event.paymentId(), event.reason())));
    }

    @KafkaListener(topics = "${order.kafka.topic.refund-completed}", groupId = "${spring.kafka.consumer.group-id}")
    public void onRefundCompleted(ConsumerRecord<String, String> record) {
        handle(
                record,
                "refund.completed",
                RefundCompletedEvent.class,
                event -> derivedEventId("refund.completed", event.refundId(), event.orderId()),
                RefundCompletedEvent::orderId,
                event -> orderEventService.handleRefundCompleted(
                        new RefundCompletedCommand(
                                event.orderId(), event.paymentId(), event.amount(), event.refundId())));
    }

    @KafkaListener(topics = "${order.kafka.topic.refund-failed}", groupId = "${spring.kafka.consumer.group-id}")
    public void onRefundFailed(ConsumerRecord<String, String> record) {
        handle(
                record,
                "refund.failed",
                RefundFailedEvent.class,
                event -> derivedEventId("refund.failed", event.refundId(), event.orderId()),
                RefundFailedEvent::orderId,
                event -> orderEventService.handleRefundFailed(
                        new RefundFailedCommand(
                                event.orderId(), event.paymentId(), event.refundId(), event.reason())));
    }

    private <T> void handle(
            ConsumerRecord<String, String> record,
            String eventType,
            Class<T> eventClass,
            Function<T, String> eventIdExtractor,
            Function<T, UUID> orderIdExtractor,
            Consumer<T> action
    ) {
        String eventId = transportEventId(record);
        UUID orderId = null;
        try {
            T event = objectMapper.readValue(record.value(), eventClass);
            eventId = eventIdExtractor.apply(event);
            orderId = orderIdExtractor.apply(event);
            inboxEventProcessor.process(eventId, eventType, record.value(), () -> action.accept(event));
            log.info("Order event consumed. eventId={}, eventType={}, orderId={}", eventId, eventType, orderId);
        } catch (Exception exception) {
            RuntimeException runtimeException = exception instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalArgumentException(eventType + " event payload is invalid", exception);
            boolean alreadyProcessed = inboxEventFailureRecorder.record(
                    eventId,
                    eventType,
                    record.value(),
                    orderId,
                    runtimeException,
                    runtimeException instanceof BusinessException);
            if (alreadyProcessed) {
                log.info("Concurrent duplicate event already processed. eventId={}, eventType={}", eventId, eventType);
                return;
            }
            log.error("Order event processing failed. eventId={}, eventType={}, orderId={}",
                    eventId, eventType, orderId, runtimeException);
            throw runtimeException;
        }
    }

    private String derivedEventId(String eventType, UUID correlationId, UUID orderId) {
        UUID id = correlationId != null ? correlationId : orderId;
        return "%s:%s:%s".formatted(eventType, id, orderId);
    }

    private String transportEventId(ConsumerRecord<String, String> record) {
        return "kafka:%s:%d:%d".formatted(record.topic(), record.partition(), record.offset());
    }
}
