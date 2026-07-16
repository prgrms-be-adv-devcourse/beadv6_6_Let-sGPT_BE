package com.openat.order.presentation.dto;

import com.openat.order.application.dto.PurchaseSignalInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record InternalPurchaseSignalResponse(
        @Schema(description = "상품 id")
        UUID productId,
        @Schema(description = "해당 상품을 포함한 주문 수", example = "2")
        long orderCount,
        @Schema(description = "해당 상품의 총 구매 수량", example = "3")
        long totalQuantity,
        @Schema(description = "가장 최근 주문 시각")
        Instant lastOrderedAt) {

    public static InternalPurchaseSignalResponse from(PurchaseSignalInfo info) {
        return new InternalPurchaseSignalResponse(
                info.productId(),
                info.orderCount(),
                info.totalQuantity(),
                info.lastOrderedAt());
    }
}
