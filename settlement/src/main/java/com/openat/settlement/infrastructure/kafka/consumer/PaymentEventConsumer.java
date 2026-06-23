package com.openat.settlement.infrastructure.kafka.consumer;

import com.openat.common.exception.BusinessException;
import com.openat.settlement.application.service.PaymentSettlementEventService;
import com.openat.settlement.domain.exception.SettlementErrorCode;
import com.openat.settlement.infrastructure.kafka.event.PaymentCompletedEvent;
import com.openat.settlement.infrastructure.kafka.event.PaymentRefundedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Payment Service의 Kafka 이벤트를 수신하는 정산 Consumer입니다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private static final String PAYMENT_COMPLETED = "PAYMENT_COMPLETED";
    private static final String PAYMENT_REFUNDED = "PAYMENT_REFUNDED";

    private final ObjectMapper objectMapper;
    private final PaymentSettlementEventService paymentSettlementEventService;

    @KafkaListener(
            topics = "${settlement.kafka.topic.payment-events}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String eventType = root.path("eventType").asString();

            if (PAYMENT_COMPLETED.equals(eventType)) {
                PaymentCompletedEvent event = objectMapper.treeToValue(root, PaymentCompletedEvent.class);
                paymentSettlementEventService.upsertSettlementOrder(event);
                log.info("PAYMENT_COMPLETED consumed. eventId={}, orderId={}, paymentId={}",
                        event.eventId(), event.orderId(), event.paymentId());
                return;
            }

            if (PAYMENT_REFUNDED.equals(eventType)) {
                PaymentRefundedEvent event = objectMapper.treeToValue(root, PaymentRefundedEvent.class);
                paymentSettlementEventService.saveSettlementRefund(event);
                log.info("PAYMENT_REFUNDED consumed. eventId={}, orderId={}, refundId={}",
                        event.eventId(), event.orderId(), event.refundId());
                return;
            }

            throw new BusinessException(
                    SettlementErrorCode.KAFKA_UNKNOWN_EVENT_TYPE,
                    "지원하지 않는 결제 이벤트 타입입니다. eventType=" + eventType
            );
        } catch (BusinessException e) {
            log.error(
                    "Payment event consume failed. errorCode={}, message={}",
                    e.getErrorCode().getCode(),
                    message,
                    e
            );
            throw e;
        } catch (Exception e) {
            log.error("Payment event consume failed. message={}", message, e);
            throw new BusinessException(
                    SettlementErrorCode.KAFKA_CONSUME_FAILED,
                    SettlementErrorCode.KAFKA_CONSUME_FAILED.getMessage(),
                    e
            );
        }
    }
}
