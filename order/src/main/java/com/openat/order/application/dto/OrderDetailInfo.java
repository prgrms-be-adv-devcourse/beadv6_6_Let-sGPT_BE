package com.openat.order.application.dto;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderDetailInfo(
        UUID orderId,
        String orderNumber,
        UUID dropId,
        UUID productId,
        String productName,
        int quantity,
        long totalPrice,
        OrderStatus status,
        UUID paymentId,
        Instant paymentExpiresAt,
        OrderFailCode failCode,
        Instant createdAt) {

    public static OrderDetailInfo from(Order order) {
        return new OrderDetailInfo(
                order.getId(),
                order.getOrderNumber(),
                order.getDropId(),
                order.getProductId(),
                order.getProductName(),
                order.getQuantity(),
                order.getTotalPrice(),
                order.getStatus(),
                order.getPaymentId(),
                order.getPaymentExpiresAt(),
                order.getFailCode(),
                order.getCreatedAt());
    }
}
