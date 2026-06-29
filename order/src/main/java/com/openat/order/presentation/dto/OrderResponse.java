package com.openat.order.presentation.dto;

import com.openat.order.application.dto.OrderDetailInfo;
import com.openat.order.domain.model.OrderFailCode;
import com.openat.order.domain.model.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        @Schema(description = "주문 id")
        UUID orderId,
        @Schema(description = "외부 노출용 주문 번호")
        String orderNumber,
        @Schema(description = "주문 대상 드롭 id")
        UUID dropId,
        @Schema(description = "상품 id")
        UUID productId,
        @Schema(description = "주문 수량", example = "1")
        int quantity,
        @Schema(description = "총 결제 금액", example = "59000")
        long totalPrice,
        @Schema(description = "주문 상태")
        OrderStatus status,
        @Schema(description = "결제 id, 결제 완료 전에는 null")
        UUID paymentId,
        @Schema(description = "결제 가능 만료 시각")
        Instant paymentExpiresAt,
        @Schema(description = "주문 실패 코드, 실패 전에는 null")
        OrderFailCode failCode,
        @Schema(description = "주문 생성 시각")
        Instant createdAt) {

    public static OrderResponse from(OrderDetailInfo info) {
        return new OrderResponse(
                info.orderId(),
                info.orderNumber(),
                info.dropId(),
                info.productId(),
                info.quantity(),
                info.totalPrice(),
                info.status(),
                info.paymentId(),
                info.paymentExpiresAt(),
                info.failCode(),
                info.createdAt());
    }
}
