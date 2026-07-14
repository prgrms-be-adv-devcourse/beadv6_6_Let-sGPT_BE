package com.openat.product.application.dto;

import com.openat.category.domain.model.Category;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductTombstone;
import java.time.Instant;
import java.util.UUID;

public record ProductChangeInfo(
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

  public static ProductChangeInfo upsert(Product product, String sellerName, Instant changedAfter) {
    ProductChangeOperation operation = ProductChangeOperation.UPDATE;
    if (product.getCreatedAt().isAfter(changedAfter)) {
      operation = ProductChangeOperation.INSERT;
    }

    Category category = product.getCategory();
    UUID categoryId = null;
    String categoryName = null;
    if (category != null) {
      categoryId = category.getId();
      categoryName = category.getName();
    }

    return new ProductChangeInfo(
        operation,
        product.getId(),
        product.getName(),
        product.getDescription(),
        categoryId,
        categoryName,
        sellerName,
        product.getPrice(),
        product.getThumbnailKey(),
        product.getCreatedAt(),
        product.getUpdatedAt(),
        null);
  }

  public static ProductChangeInfo deletion(ProductTombstone tombstone) {
    return new ProductChangeInfo(
        ProductChangeOperation.DELETE,
        tombstone.id(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        tombstone.deletedAt());
  }

  public Instant changedAt() {
    if (operation == ProductChangeOperation.DELETE) {
      return deletedAt;
    }
    if (updatedAt.isAfter(createdAt)) {
      return updatedAt;
    }
    return createdAt;
  }
}
