package com.openat.order.presentation.dto;

import com.openat.order.application.dto.CreateOrderCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateOrderRequest(
        @Schema(description = "주문 대상 드롭 id")
        @NotNull UUID dropId,
        @Schema(description = "주문 수량", example = "1")
        @Min(1) int quantity,
        @Schema(description = "주문 생성 멱등키", example = "order-20260626-0001")
        @NotBlank String idempotencyKey,
        @Schema(description = "주문 표시명(프론트가 조회한 상품명)")
        @Size(max = 255) String orderName) {

    public CreateOrderCommand toCommand() {
        return new CreateOrderCommand(dropId, quantity, idempotencyKey, orderName);
    }
}
