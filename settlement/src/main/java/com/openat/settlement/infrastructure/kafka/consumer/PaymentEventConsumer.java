package com.openat.settlement.infrastructure.kafka.consumer;

import com.openat.common.exception.BusinessException;
import com.openat.settlement.application.service.PaymentSettlementEventService;
import com.openat.settlement.domain.exception.SettlementErrorCode;
import com.openat.settlement.infrastructure.acl.PaymentEventAclMapper;
import com.openat.settlement.infrastructure.kafka.event.PaymentCompletedEvent;
import com.openat.settlement.infrastructure.kafka.event.PaymentRefundedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Payment Service의 Kafka 이벤트를 수신하는 정산 Consumer입니다.
 *
 *
 * [Kafka payment-events 토픽에서 이벤트 수신]
 *         ↓
 * [PaymentEventConsumer]
 *         ↓
 * [paymentEventAclMapper로 정산 서비스용 Command로 변환]
 *         ↓
 * [SettlementOrderUseCase 또는 SettlementService 호출]
 *         ↓
 * [settlement_order / settlement_refund 저장 또는 수정]
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private static final String PAYMENT_COMPLETED = "PaymentSettlementCompleted";
    private static final String PAYMENT_REFUNDED = "RefundSettlementCompleted";
    private static final DateTimeFormatter SETTLEMENT_MONTH_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMM");

    private final ObjectMapper objectMapper;
    private final PaymentSettlementEventService paymentSettlementEventService;
    private final PaymentEventAclMapper paymentEventAclMapper;

    @KafkaListener(
            topics = "${settlement.kafka.topic.payment-events}",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            String eventType = root.path("eventType").asString();

            if (PAYMENT_COMPLETED.equals(eventType)) {
                applyPaymentCompletedDefaults(root);
                PaymentCompletedEvent event = objectMapper.treeToValue(root, PaymentCompletedEvent.class);

                paymentSettlementEventService.upsertSettlementOrder(paymentEventAclMapper.toCommand(event));
                log.info("PAYMENT_COMPLETED consumed. eventId={}, orderId={}, paymentId={}",
                        event.eventId(), event.orderId(), event.paymentId());
                return;
            }

            if (PAYMENT_REFUNDED.equals(eventType)) {
                PaymentRefundedEvent event = objectMapper.treeToValue(root, PaymentRefundedEvent.class);
                paymentSettlementEventService.saveSettlementRefund(paymentEventAclMapper.toCommand(event));
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

    private void applyPaymentCompletedDefaults(JsonNode root) {
        if (!(root instanceof ObjectNode objectNode)) {
            return;
        }

        JsonNode feeAmountNode = objectNode.path("feeAmount");
        if (feeAmountNode.isMissingNode() || feeAmountNode.isNull()) {
            objectNode.put("feeAmount", 0L);
        }

        JsonNode settlementMonthNode = objectNode.path("settlementMonth");
        if (!settlementMonthNode.isMissingNode()
                && !settlementMonthNode.isNull()
                && !settlementMonthNode.asString().isBlank()) {
            return;
        }

        String paidAtText = objectNode.path("paidAt").asString(null);
        if (paidAtText == null || paidAtText.isBlank()) {
            return;
        }

        String settlementMonth = LocalDateTime.parse(paidAtText).format(SETTLEMENT_MONTH_FORMATTER);
        objectNode.put("settlementMonth", settlementMonth);
    }
}
