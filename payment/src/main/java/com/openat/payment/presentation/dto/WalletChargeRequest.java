package com.openat.payment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record WalletChargeRequest(
        @Schema(description = "충전 금액(원)", example = "50000") Long amount,
        @Schema(description = "충전수단", example = "MOCK", allowableValues = {"MOCK", "PG"}) String method) {
}
