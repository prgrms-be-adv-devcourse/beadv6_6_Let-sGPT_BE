package com.openat.payment.infrastructure.messaging;

import java.time.LocalDateTime;
import java.util.UUID;

// order_completed.events 페이로드(api_event_specification.md) — sellerId/productId 사후채움(B2)용.
public record OrderCompletedEvent(UUID orderId, UUID sellerId, UUID productId, UUID memberId, Long amount,
        LocalDateTime completedAt) {
}
