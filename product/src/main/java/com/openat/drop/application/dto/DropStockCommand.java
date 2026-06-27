package com.openat.drop.application.dto;

import com.openat.drop.domain.repository.StockMutation;
import java.util.UUID;

public record DropStockCommand(UUID dropId, UUID orderId, UUID buyerId, int quantity) {

  public StockMutation toMutation() {
    return new StockMutation(dropId, orderId, buyerId, quantity);
  }
}
