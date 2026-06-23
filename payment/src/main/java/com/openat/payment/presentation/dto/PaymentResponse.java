package com.openat.payment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record PaymentResponse(
        @Schema(description = "결제 ID") UUID paymentId,
        @Schema(description = "결제 상태", example = "APPROVED") String status,
        @Schema(description = "PG 결제 키(PENDING 상태에서는 null)", nullable = true) String paymentKey) {

    public static PaymentResponse of(UUID paymentId, String status) {
        return new PaymentResponse(paymentId, status, null);
    }
}
