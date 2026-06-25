package com.openat.order.application.dto;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderValidationInfo(
        UUID orderId,
        UUID memberId,
        UUID sellerId,
        UUID dropId,
        UUID productId,
        long amount,
        OrderStatus status,
        Instant paymentExpiresAt) {

    public static OrderValidationInfo from(Order order) {
        return new OrderValidationInfo(
                order.getId(),
                order.getMemberId(),
                order.getSellerId(),
                order.getDropId(),
                order.getProductId(),
                order.getTotalPrice(),
                order.getStatus(),
                order.getPaymentExpiresAt());
    }
}
