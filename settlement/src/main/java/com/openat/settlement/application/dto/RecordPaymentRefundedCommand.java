package com.openat.settlement.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record RecordPaymentRefundedCommand(
        String eventId,
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
