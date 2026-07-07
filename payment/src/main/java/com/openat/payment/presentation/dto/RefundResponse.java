package com.openat.payment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record RefundResponse(
        @Schema(description = "환불 ID") UUID refundId,
        @Schema(description = "환불 대상 결제 ID") UUID paymentId,
        @Schema(description = "환불 금액(원)") Long amount,
        @Schema(description = "환불 상태", example = "COMPLETE") String status) {
}
