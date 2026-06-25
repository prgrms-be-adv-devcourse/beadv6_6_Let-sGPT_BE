package com.openat.order.presentation.dto;

import com.openat.order.application.dto.OrderInfo;
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

    public static CreateOrderResponse from(OrderInfo info) {
        return new CreateOrderResponse(
                info.orderId(),
                info.orderNumber(),
                info.status(),
                info.totalPrice(),
                info.productName(),
                info.paymentExpiresAt());
    }
}
