package com.openat.drop.presentation.dto;

import com.openat.drop.domain.model.Drop;
import java.util.UUID;

public record OrderSnapshotResponse(
    UUID productId,
    UUID sellerId,
    String productName,
    long unitPrice) {

  public static OrderSnapshotResponse from(Drop drop) {
    return new OrderSnapshotResponse(
        drop.getProduct().getId(),
        drop.getProduct().getSellerId(),
        drop.getProduct().getName(),
        drop.getDropPrice());
  }
}
