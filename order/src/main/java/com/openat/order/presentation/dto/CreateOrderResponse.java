package com.openat.order.presentation.dto;

import com.openat.order.application.dto.CreateOrderResult;
import com.openat.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record CreateOrderResponse(
        UUID orderId,
        String orderNumber,
        OrderStatus status,
        long amount,
        String orderName,
        Instant paymentExpiresAt) {

    public static CreateOrderResponse from(CreateOrderResult result) {
        return new CreateOrderResponse(
                result.orderId(),
                result.orderNumber(),
                result.status(),
                result.amount(),
                result.orderName(),
                result.paymentExpiresAt());
    }
}
