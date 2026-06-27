package com.openat.drop.presentation.dto;

import com.openat.drop.application.dto.DropStockCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;

public record StockChangeRequest(
    @NotNull UUID orderId, @NotNull UUID buyerId, @Positive int quantity) {

  public DropStockCommand toCommand(UUID dropId) {
    return new DropStockCommand(dropId, orderId, buyerId, quantity);
  }
}
