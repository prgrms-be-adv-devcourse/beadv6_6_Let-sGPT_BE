package com.openat.payment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record PaymentRequest(
        @Schema(description = "주문 ID") UUID orderId,
        @Schema(description = "결제 금액(원)", example = "10000") Long amount,
        @Schema(description = "결제수단", example = "WALLET", allowableValues = {"WALLET", "PG"}) String method) {
}
