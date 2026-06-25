package com.openat.order.presentation.dto;

import com.openat.order.application.dto.CreateOrderCommand;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateOrderRequest(
        @NotNull UUID dropId,
        @Min(1) int quantity,
        @NotBlank String idempotencyKey) {

    public CreateOrderCommand toCommand(UUID memberId) {
        return new CreateOrderCommand(memberId, dropId, quantity, idempotencyKey);
    }
}
