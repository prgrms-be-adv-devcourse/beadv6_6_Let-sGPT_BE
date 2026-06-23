package com.openat.payment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

// E1 — 브라우저가 토스 successUrl 리다이렉트(또는 그 결과를 받은 프론트 페이지)에서 전달받은 값을 그대로 넘긴다.
public record WalletChargeConfirmRequest(
        @Schema(description = "충전 ID") UUID chargeId,
        @Schema(description = "충전 금액(원)", example = "50000") Long amount,
        @Schema(description = "토스 SDK가 발급한 결제 키") String paymentKey) {
}
