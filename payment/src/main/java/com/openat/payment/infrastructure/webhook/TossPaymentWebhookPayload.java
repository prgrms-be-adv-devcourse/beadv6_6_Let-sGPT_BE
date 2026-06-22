package com.openat.payment.infrastructure.webhook;

// api_event_specification.md POST /api/v1/payments/webhook 페이로드 — { paymentKey, orderId, status, pgTxId }.
// 실제 토스 웹훅의 정확한 envelope(eventType/data 래핑 등)은 ngrok 실연동 시 확인 필요(qna.md 논의 보류 사항).
public record TossPaymentWebhookPayload(String paymentKey, String orderId, String status, String pgTxId) {
}
