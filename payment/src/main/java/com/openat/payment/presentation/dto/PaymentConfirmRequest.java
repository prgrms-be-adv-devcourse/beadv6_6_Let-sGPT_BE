package com.openat.payment.presentation.dto;

import java.util.UUID;

// A16 — 브라우저가 토스 successUrl 리다이렉트(또는 그 결과를 받은 프론트 페이지)에서 전달받은 값을 그대로 넘긴다.
public record PaymentConfirmRequest(UUID orderId, Long amount, String paymentKey) {
}
