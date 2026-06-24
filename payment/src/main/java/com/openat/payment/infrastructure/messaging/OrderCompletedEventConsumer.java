package com.openat.payment.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.payment.application.usecase.PaymentUseCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// order_completed.events 구독 — 결제가 §5.5 원칙("이벤트 구독자=주문만")의 신규 예외로 추가됨(B2).
@Slf4j
@Component
public class OrderCompletedEventConsumer {

    private final PaymentUseCase paymentUseCase;
    private final ObjectMapper objectMapper;

    public OrderCompletedEventConsumer(PaymentUseCase paymentUseCase, ObjectMapper objectMapper) {
        this.paymentUseCase = paymentUseCase;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = "order_completed.events", groupId = "payment-service")
    public void onOrderCompleted(String message) {
        try {
            OrderCompletedEvent event = objectMapper.readValue(message, OrderCompletedEvent.class);
            paymentUseCase.backfillSellerAndProduct(event.orderId(), event.sellerId(), event.productId());
        } catch (Exception e) {
            log.error("[OrderCompletedEventConsumer] 처리 실패: {}", message, e);
        }
    }
}
