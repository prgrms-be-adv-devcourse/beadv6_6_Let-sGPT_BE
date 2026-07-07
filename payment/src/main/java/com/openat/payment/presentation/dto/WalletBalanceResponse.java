package com.openat.payment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record WalletBalanceResponse(
        @Schema(description = "지갑 잔액(원)", example = "1000000") long balance) {
}
