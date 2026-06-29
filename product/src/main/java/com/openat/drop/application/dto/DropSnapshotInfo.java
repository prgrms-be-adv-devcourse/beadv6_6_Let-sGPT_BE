package com.openat.drop.application.dto;

import com.openat.drop.domain.model.Drop;
import com.openat.product.domain.model.Product;
import java.util.UUID;

public record DropSnapshotInfo(UUID productId, UUID sellerId, long unitPrice) {

  public static DropSnapshotInfo from(Drop drop) {
    Product product = drop.getProduct();
    return new DropSnapshotInfo(product.getId(), product.getSellerId(), drop.getDropPrice());
  }
}
