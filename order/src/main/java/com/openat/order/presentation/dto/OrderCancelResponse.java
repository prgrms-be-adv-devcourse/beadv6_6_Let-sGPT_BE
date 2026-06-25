package com.openat.order.presentation.dto;

import com.openat.order.application.dto.OrderCancelInfo;
import com.openat.order.domain.model.OrderStatus;
import java.util.UUID;

public record OrderCancelResponse(UUID orderId, OrderStatus status) {

    public static OrderCancelResponse from(OrderCancelInfo info) {
        return new OrderCancelResponse(info.orderId(), info.status());
    }
}
