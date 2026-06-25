package com.openat.order.presentation.dto;

import com.openat.order.application.dto.OrderDetailInfo;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
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

    public static OrderResponse from(OrderDetailInfo info) {
        return new OrderResponse(
                info.orderId(),
                info.orderNumber(),
                info.dropId(),
                info.productId(),
                info.productName(),
                info.quantity(),
                info.totalPrice(),
                info.status(),
                info.paymentId(),
                info.paymentExpiresAt(),
                info.failCode(),
                info.createdAt());
    }
}
