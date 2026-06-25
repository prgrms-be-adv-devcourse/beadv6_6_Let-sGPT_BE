package com.openat.order.application.dto;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderInfo(
        UUID orderId,
        String orderNumber,
        UUID memberId,
        UUID dropId,
        UUID productId,
        UUID sellerId,
        String productName,
        int quantity,
        long unitPrice,
        long totalPrice,
        UUID paymentId,
        OrderStatus status,
        Instant paymentExpiresAt,
        OrderFailCode failCode,
        String failMessage,
        Instant createdAt) {

    public static OrderInfo from(Order order) {
        return new OrderInfo(
                order.getId(),
                order.getOrderNumber(),
                order.getMemberId(),
                order.getDropId(),
                order.getProductId(),
                order.getSellerId(),
                order.getProductName(),
                order.getQuantity(),
                order.getUnitPrice(),
                order.getTotalPrice(),
                order.getPaymentId(),
                order.getStatus(),
                order.getPaymentExpiresAt(),
                order.getFailCode(),
                order.getFailMessage(),
                order.getCreatedAt());
    }
}
