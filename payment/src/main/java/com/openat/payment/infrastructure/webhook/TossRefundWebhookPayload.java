package com.openat.payment.infrastructure.webhook;

// api_event_specification.md POST /api/v1/refunds/webhook 페이로드 — { refundKey, refundId, status }.
// refundId가 직접 포함되어 있어 결제/충전 웹훅과 달리 키 해시 매칭이 필요 없음(findById로 바로 매칭).
public record TossRefundWebhookPayload(String refundKey, String refundId, String status) {
}
