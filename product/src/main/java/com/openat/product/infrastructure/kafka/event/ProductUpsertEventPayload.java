package com.openat.product.infrastructure.kafka.event;

import com.openat.category.domain.model.Category;
import com.openat.product.domain.model.Product;
import java.time.Instant;
import java.util.UUID;

public record ProductUpsertEventPayload(
    UUID id,
    UUID sellerId,
    String name,
    String description,
    UUID categoryId,
    String categoryName,
    String sellerName,
    Long price,
    String thumbnailKey,
    Instant createdAt,
    Instant updatedAt) {

  public static ProductUpsertEventPayload from(Product product, String sellerName) {
    UUID categoryId = null;
    String categoryName = null;
    Category category = product.getCategory();
    if (category != null) {
      categoryId = category.getId();
      categoryName = category.getName();
    }

    return new ProductUpsertEventPayload(
        product.getId(),
        product.getSellerId(),
        product.getName(),
        product.getDescription(),
        categoryId,
        categoryName,
        sellerName,
        product.getPrice(),
        product.getThumbnailKey(),
        product.getCreatedAt(),
        product.getUpdatedAt());
  }
}
