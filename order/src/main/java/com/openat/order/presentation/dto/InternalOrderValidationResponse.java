package com.openat.order.presentation.dto;

import com.openat.order.application.dto.PaymentValidationInfo;
import com.openat.order.domain.model.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record InternalOrderValidationResponse(
        @Schema(description = "주문 id")
        UUID orderId,
        @Schema(description = "구매자 회원 id")
        UUID memberId,
        @Schema(description = "판매자 또는 판매처 식별자")
        UUID sellerId,
        @Schema(description = "주문 대상 드롭 id")
        UUID dropId,
        @Schema(description = "상품 id")
        UUID productId,
        @Schema(description = "결제 검증 기준 금액", example = "59000")
        long amount,
        @Schema(description = "주문 상태")
        OrderStatus status,
        @Schema(description = "결제 가능 만료 시각")
        Instant paymentExpiresAt) {

    public static InternalOrderValidationResponse from(PaymentValidationInfo info) {
        return new InternalOrderValidationResponse(
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
