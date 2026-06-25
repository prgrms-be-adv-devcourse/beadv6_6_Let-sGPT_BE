package com.openat.order.presentation.dto;

import com.openat.order.application.dto.OrderValidationInfo;
import com.openat.order.domain.model.OrderStatus;
import java.time.Instant;
import java.util.UUID;

public record OrderValidationResponse(
        UUID orderId,
        UUID memberId,
        UUID sellerId,
        UUID dropId,
        UUID productId,
        long amount,
        OrderStatus status,
        Instant paymentExpiresAt) {

    public static OrderValidationResponse from(OrderValidationInfo info) {
        return new OrderValidationResponse(
                info.orderId(),
                info.memberId(),
                info.sellerId(),
                info.dropId(),
                info.productId(),
                info.amount(),
                info.status(),
                info.paymentExpiresAt());
    }
}
