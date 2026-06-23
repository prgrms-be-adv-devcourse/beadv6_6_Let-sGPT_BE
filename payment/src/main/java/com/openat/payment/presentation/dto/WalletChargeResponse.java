package com.openat.payment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record WalletChargeResponse(
        @Schema(description = "충전 ID") UUID chargeId,
        @Schema(description = "충전 상태", example = "APPROVED") String status) {
}
