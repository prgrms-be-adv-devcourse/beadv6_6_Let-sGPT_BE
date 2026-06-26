package com.openat.payment.infrastructure.messaging;

import java.util.UUID;

// order.completed.events 페이로드 — sellerId/productId 사후채움(B2)용.
public record OrderCompletedEvent(UUID orderId, UUID sellerId, UUID productId, UUID memberId, Long amount) {
}
