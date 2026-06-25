package com.openat.order.application.dto;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record PaymentValidationInfo(
        UUID orderId,
        UUID memberId,
        UUID sellerId,
        UUID dropId,
        UUID productId,
        long amount,
        OrderStatus status,
        Instant paymentExpiresAt) {

    public static PaymentValidationInfo from(Order order) {
        return new PaymentValidationInfo(
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
