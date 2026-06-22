package com.openat.payment.infrastructure.webhook;

// api_event_specification.md POST /api/v1/wallet/charge/webhook 페이로드 — { paymentKey, chargeId, status }.
public record TossWalletChargeWebhookPayload(String paymentKey, String chargeId, String status) {
}
