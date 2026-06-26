package com.openat.order.infrastructure.kafka.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.common.exception.BusinessException;
import com.openat.order.application.dto.PaymentCompletedCommand;
import com.openat.order.application.dto.PaymentFailedCommand;
import com.openat.order.application.dto.RefundCompletedCommand;
import com.openat.order.application.dto.RefundFailedCommand;
import com.openat.order.application.service.OrderEventService;
import com.openat.order.infrastructure.kafka.event.PaymentCompleteEvent;
import com.openat.order.infrastructure.kafka.event.PaymentFailedEvent;
import com.openat.order.infrastructure.kafka.event.RefundCompletedEvent;
import com.openat.order.infrastructure.kafka.event.RefundFailedEvent;
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

    @KafkaListener(
            topics = "${order.kafka.topic.payment-complete}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onPaymentCompleted(ConsumerRecord<String, String> record) {
        String payload = record.value();
        try {
            PaymentCompleteEvent event = objectMapper.readValue(payload, PaymentCompleteEvent.class);
            orderEventService.handlePaymentCompleted(new PaymentCompletedCommand(
                    event.orderId(), event.version(), event.paymentId(), event.amount()
            ));
            log.info("payment.complete.events consumed. orderId={}", event.orderId());
        } catch (BusinessException e) {
            log.error("payment.complete.events consume failed. payload={}", payload, e);
            throw e;
        } catch (Exception e) {
            log.error("payment.complete.events consume failed. payload={}", payload, e);
            throw new RuntimeException("payment.complete.events consume failed", e);
        }
    }

    @KafkaListener(
            topics = "${order.kafka.topic.payment-failed}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onPaymentFailed(ConsumerRecord<String, String> record) {
        String payload = record.value();
        try {
            PaymentFailedEvent event = objectMapper.readValue(payload, PaymentFailedEvent.class);
            orderEventService.handlePaymentFailed(new PaymentFailedCommand(
                    event.orderId(), event.version(), event.paymentId(), event.reason()
            ));
            log.info("payment.failed.events consumed. orderId={}", event.orderId());
        } catch (BusinessException e) {
            log.error("payment.failed.events consume failed. payload={}", payload, e);
            throw e;
        } catch (Exception e) {
            log.error("payment.failed.events consume failed. payload={}", payload, e);
            throw new RuntimeException("payment.failed.events consume failed", e);
        }
    }

    @KafkaListener(
            topics = "${order.kafka.topic.refund-completed}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onRefundCompleted(ConsumerRecord<String, String> record) {
        String payload = record.value();
        try {
            RefundCompletedEvent event = objectMapper.readValue(payload, RefundCompletedEvent.class);
            orderEventService.handleRefundCompleted(new RefundCompletedCommand(
                    event.orderId(), event.version(), event.paymentId(), event.amount(), event.refundId()
            ));
            log.info("refund.completed.events consumed. orderId={}", event.orderId());
        } catch (BusinessException e) {
            log.error("refund.completed.events consume failed. payload={}", payload, e);
            throw e;
        } catch (Exception e) {
            log.error("refund.completed.events consume failed. payload={}", payload, e);
            throw new RuntimeException("refund.completed.events consume failed", e);
        }
    }

    @KafkaListener(
            topics = "${order.kafka.topic.refund-failed}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onRefundFailed(ConsumerRecord<String, String> record) {
        String payload = record.value();
        try {
            RefundFailedEvent event = objectMapper.readValue(payload, RefundFailedEvent.class);
            orderEventService.handleRefundFailed(new RefundFailedCommand(
                    event.orderId(), event.version(), event.paymentId(), event.refundId(), event.reason()
            ));
            log.info("refund.failed.events consumed. orderId={}", event.orderId());
        } catch (BusinessException e) {
            log.error("refund.failed.events consume failed. payload={}", payload, e);
            throw e;
        } catch (Exception e) {
            log.error("refund.failed.events consume failed. payload={}", payload, e);
            throw new RuntimeException("refund.failed.events consume failed", e);
        }
    }
}
