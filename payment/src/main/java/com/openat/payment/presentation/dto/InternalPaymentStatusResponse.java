package com.openat.payment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record InternalPaymentStatusResponse(
    @Schema(description = "결제 ID") UUID paymentId,
    @Schema(description = "결제 상태(Payment.Status 7종)", example = "APPROVED") String status,
    @Schema(description = "결제 금액(원)", example = "10000") Long amount) {}
