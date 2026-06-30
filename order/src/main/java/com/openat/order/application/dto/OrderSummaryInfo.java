package com.openat.order.application.dto;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderSummaryInfo(
        UUID orderId,
        String orderNumber,
        UUID dropId,
        UUID productId,
        String productName,
        int quantity,
        long totalPrice,
        OrderStatus status,
        Instant createdAt) {

    public static OrderSummaryInfo from(Order order) {
        return new OrderSummaryInfo(
                order.getId(),
                order.getOrderNumber(),
                order.getDropId(),
                order.getProductId(),
                order.getProductName(),
                order.getQuantity(),
                order.getTotalPrice(),
                order.getStatus(),
                order.getCreatedAt());
    }
}
