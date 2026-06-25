package com.openat.payment.infrastructure.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 실제 토스 웹훅 envelope({createdAt, eventType, data:{...}}) 확인 완료(2026-06-24, ngrok 실연동) —
// I1이 payload.status()를 더 이상 안 믿고 paymentKey로 재조회만 하므로 그 필드만 추출.
@JsonIgnoreProperties(ignoreUnknown = true)
public record TossPaymentWebhookPayload(String paymentKey) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Envelope(TossPaymentWebhookPayload data) {
    }
}
