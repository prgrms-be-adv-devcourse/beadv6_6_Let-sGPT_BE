package com.openat.order.presentation.dto;

import com.openat.order.application.dto.OrderInfo;
import com.openat.order.domain.model.OrderStatus;
import java.util.UUID;

public record CancelOrderResponse(UUID orderId, OrderStatus status) {

    public static CancelOrderResponse from(OrderInfo info) {
        return new CancelOrderResponse(info.orderId(), info.status());
    }
}
