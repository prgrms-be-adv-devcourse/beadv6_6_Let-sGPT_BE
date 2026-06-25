package com.openat.product.application.dto;

import com.openat.category.domain.model.Category;
import com.openat.product.domain.model.Product;
import java.time.Instant;
import java.util.UUID;

public record ProductInfo(
    UUID id,
    UUID sellerId,
    String name,
    String description,
    UUID categoryId,
    String categoryName,
    Long price,
    String thumbnailKey,
    Instant createdAt) {

  public static ProductInfo from(Product product) {
    Category category = product.getCategory();
    UUID categoryId = null;
    String categoryName = null;
    if (category != null) {
      categoryId = category.getId();
      categoryName = category.getName();
    }
    return new ProductInfo(
        product.getId(),
        product.getSellerId(),
        product.getName(),
        product.getDescription(),
        categoryId,
        categoryName,
        product.getPrice(),
        product.getThumbnailKey(),
        product.getCreatedAt());
  }
}
