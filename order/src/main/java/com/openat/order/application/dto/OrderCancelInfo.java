package com.openat.order.application.dto;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import java.util.UUID;

public record OrderCancelInfo(UUID orderId, OrderStatus status) {

    public static OrderCancelInfo from(Order order) {
        return new OrderCancelInfo(order.getId(), order.getStatus());
    }
}
