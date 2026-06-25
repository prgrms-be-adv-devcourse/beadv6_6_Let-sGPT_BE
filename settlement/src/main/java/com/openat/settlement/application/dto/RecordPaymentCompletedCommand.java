package com.openat.settlement.application.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record RecordPaymentCompletedCommand(
        String eventId,
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
