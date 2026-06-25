package com.openat.order.presentation.dto;

import com.openat.order.application.dto.OrderSummaryInfo;
import com.openat.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderSummaryResponse(
        UUID orderId,
        String orderNumber,
        UUID dropId,
        UUID productId,
        String productName,
        int quantity,
        long totalPrice,
        OrderStatus status,
        Instant createdAt) {

    public static OrderSummaryResponse from(OrderSummaryInfo info) {
        return new OrderSummaryResponse(
                info.orderId(),
                info.orderNumber(),
                info.dropId(),
                info.productId(),
                info.productName(),
                info.quantity(),
                info.totalPrice(),
                info.status(),
                info.createdAt());
    }
}
