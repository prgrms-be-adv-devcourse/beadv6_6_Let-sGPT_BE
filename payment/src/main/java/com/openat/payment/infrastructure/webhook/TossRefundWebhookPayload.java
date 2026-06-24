package com.openat.payment.infrastructure.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 실제 토스 웹훅 envelope({createdAt, eventType, data:{...}}) — Payment/WalletCharge와 동일 패턴(2026-06-24).
// refundId는 우리 DB 자체 PK라 토스가 보낼 수 없으므로 제거, paymentKey로만 식별한다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossRefundWebhookPayload(String paymentKey) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envelope(TossRefundWebhookPayload data) {
    }
}
