package com.openat.order.infrastructure.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.order.application.dto.PaymentCompletedCommand;
import com.openat.order.application.dto.PaymentFailedCommand;
import com.openat.order.application.dto.RefundCompletedCommand;
import com.openat.order.application.dto.RefundFailedCommand;
import com.openat.order.application.usecase.OrderCommandUseCase;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private final ObjectMapper objectMapper;
    private final OrderCommandUseCase orderCommandUseCase;

    @KafkaListener(topics = "payment_completed_events", groupId = "order-service")
    public void onPaymentCompleted(String message) throws Exception {
        JsonNode event = objectMapper.readTree(message);
        JsonNode payload = event.path("payload");
        orderCommandUseCase.completePayment(new PaymentCompletedCommand(
                UUID.fromString(payload.path("orderId").asText()),
                UUID.fromString(payload.path("paymentId").asText()),
                payload.path("amount").asLong(),
                occurredAt(event),
                event.path("eventId").asText()));
    }

    @KafkaListener(topics = "payment_failed_events", groupId = "order-service")
    public void onPaymentFailed(String message) throws Exception {
        JsonNode event = objectMapper.readTree(message);
        JsonNode payload = event.path("payload");
        orderCommandUseCase.failPayment(new PaymentFailedCommand(
                UUID.fromString(payload.path("orderId").asText()),
                payload.path("reason").asText(),
                occurredAt(event),
                event.path("eventId").asText()));
    }

    @KafkaListener(topics = "refund_completed_events", groupId = "order-service")
    public void onRefundCompleted(String message) throws Exception {
        JsonNode event = objectMapper.readTree(message);
        JsonNode payload = event.path("payload");
        orderCommandUseCase.completeRefund(new RefundCompletedCommand(
                UUID.fromString(payload.path("orderId").asText()),
                payload.path("amount").asLong(),
                occurredAt(event),
                event.path("eventId").asText()));
    }

    @KafkaListener(topics = "refund_failed_events", groupId = "order-service")
    public void onRefundFailed(String message) throws Exception {
        JsonNode event = objectMapper.readTree(message);
        JsonNode payload = event.path("payload");
        orderCommandUseCase.failRefund(new RefundFailedCommand(
                UUID.fromString(payload.path("orderId").asText()),
                payload.path("reason").asText(),
                event.path("eventId").asText()));
    }

    private Instant occurredAt(JsonNode event) {
        String raw = event.path("occurredAt").asText(null);
        return raw == null ? Instant.now() : Instant.parse(raw);
    }
}
