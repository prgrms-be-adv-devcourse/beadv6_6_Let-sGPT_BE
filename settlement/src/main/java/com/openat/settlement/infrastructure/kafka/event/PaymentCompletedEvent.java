package com.openat.settlement.infrastructure.kafka.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Payment Service가 결제 완료 시 발행하는 이벤트입니다.
 */
public record PaymentCompletedEvent(
        String eventId,
        String eventType,
        LocalDateTime occurredAt,

        UUID paymentId,
        UUID orderId,
        UUID sellerId,
        UUID buyerId,
        UUID productId,

        String settlementMonth,
        long orderAmount,
        long paidAmount,
        long feeAmount,
        LocalDateTime paidAt
) {
}
