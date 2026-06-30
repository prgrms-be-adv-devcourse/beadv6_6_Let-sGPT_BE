package com.openat.order.application.dto;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record CreateOrderResult(
        UUID orderId,
        String orderNumber,
        OrderStatus status,
        long amount,
        String orderName,
        Instant paymentExpiresAt,
        boolean created) {

    public static CreateOrderResult from(Order order) {
        return from(order, true);
    }

    public static CreateOrderResult from(Order order, boolean created) {
        return new CreateOrderResult(
                order.getId(),
                order.getOrderNumber(),
                order.getStatus(),
                order.getTotalPrice(),
                order.getProductName(),
                order.getPaymentExpiresAt(),
                created);
    }
}
