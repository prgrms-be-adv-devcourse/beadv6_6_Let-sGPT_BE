package com.openat.settlement.infrastructure.kafka.event;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Payment Service가 환불 완료 시 발행하는 이벤트입니다.
 */
public record PaymentRefundedEvent(
        String eventId,
        String eventType,
        LocalDateTime occurredAt,

        UUID refundId,
        UUID paymentId,
        UUID orderId,
        UUID sellerId,
        UUID buyerId,

        long refundAmount,
        String refundReason,
        String settlementMonth,
        LocalDateTime refundedAt
) {
}
