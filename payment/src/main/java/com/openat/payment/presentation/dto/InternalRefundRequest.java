package com.openat.payment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record InternalRefundRequest(
    @Schema(description = "환불 대상 주문 ID") UUID orderId) {}
