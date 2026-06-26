package com.openat.order.presentation.dto;

import com.openat.order.application.dto.OrderCancelInfo;
import com.openat.order.domain.model.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record OrderCancelResponse(
        @Schema(description = "주문 id")
        UUID orderId,
        @Schema(description = "취소 요청 처리 후 주문 상태")
        OrderStatus status) {

    public static OrderCancelResponse from(OrderCancelInfo info) {
        return new OrderCancelResponse(info.orderId(), info.status());
    }
}
