package com.openat.order.presentation.dto;

import com.openat.order.application.dto.CreateOrderResult;
import com.openat.order.domain.model.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record CreateOrderResponse(
        @Schema(description = "주문 id")
        UUID orderId,
        @Schema(description = "외부 노출용 주문 번호")
        String orderNumber,
        @Schema(description = "주문 상태")
        OrderStatus status,
        @Schema(description = "총 결제 금액", example = "59000")
        long amount,
        @Schema(description = "결제 가능 만료 시각")
        Instant paymentExpiresAt) {

    public static CreateOrderResponse from(CreateOrderResult result) {
        return new CreateOrderResponse(
                result.orderId(),
                result.orderNumber(),
                result.status(),
                result.amount(),
                result.paymentExpiresAt());
    }
}
