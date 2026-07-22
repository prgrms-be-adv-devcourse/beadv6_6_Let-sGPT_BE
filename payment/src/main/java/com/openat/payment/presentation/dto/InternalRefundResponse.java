package com.openat.payment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record InternalRefundResponse(
    @Schema(description = "처리 결과", example = "REFUND_ACCEPTED",
            allowableValues = {"NO_PAYMENT", "REFUND_ACCEPTED", "PAYMENT_PENDING"})
        String result) {}
