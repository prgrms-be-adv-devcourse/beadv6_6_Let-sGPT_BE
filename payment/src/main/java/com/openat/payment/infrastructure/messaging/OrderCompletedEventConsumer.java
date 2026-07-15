package com.openat.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.payment.application.usecase.PaymentUseCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// order_completed.events 구독 — 결제가 §5.5 원칙("이벤트 구독자=주문만")의 신규 예외로 추가됨(B2).
// 예외를 삼키지 않고 그대로 던진다(7/15 DLQ WS-1) — KafkaConsumerErrorHandlerConfig의 DefaultErrorHandler가
// 3회 재시도 후 order.completed.events.DLQ로 보낸다. backfillSellerAndProduct는 멱등이라 재시도 안전.
@Slf4j
@Component
public class OrderCompletedEventConsumer {

    private final PaymentUseCase paymentUseCase;
    private final ObjectMapper objectMapper;

    public OrderCompletedEventConsumer(PaymentUseCase paymentUseCase, ObjectMapper objectMapper) {
        this.paymentUseCase = paymentUseCase;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "order.completed.events", groupId = "payment-service")
    public void onOrderCompleted(String message) {
        OrderCompletedEvent event;
        try {
            event = objectMapper.readValue(message, OrderCompletedEvent.class);
        } catch (Exception e) {
            log.error("[OrderCompletedEventConsumer] 역직렬화 실패, DLQ로 격리: {}", message, e);
            throw new IllegalStateException("order.completed.events 역직렬화 실패", e);
        }
        paymentUseCase.backfillSellerAndProduct(event.orderId(), event.sellerId(), event.productId());
    }
}
