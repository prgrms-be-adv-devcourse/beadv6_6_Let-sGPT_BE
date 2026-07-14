package com.openat.product.presentation.dto;

import com.openat.product.application.dto.ProductChangeInfo;
import com.openat.product.application.dto.ProductChangeOperation;
import java.time.Instant;
import java.util.UUID;

public record ProductChangeResponse(
    ProductChangeOperation operation,
    UUID id,
    String name,
    String description,
    UUID categoryId,
    String categoryName,
    String sellerName,
    Long price,
    String thumbnailKey,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public static ProductChangeResponse from(ProductChangeInfo info) {
    return new ProductChangeResponse(
        info.operation(),
        info.id(),
        info.name(),
        info.description(),
        info.categoryId(),
        info.categoryName(),
        info.sellerName(),
        info.price(),
        info.thumbnailKey(),
        info.createdAt(),
        info.updatedAt(),
        info.deletedAt());
  }
}
