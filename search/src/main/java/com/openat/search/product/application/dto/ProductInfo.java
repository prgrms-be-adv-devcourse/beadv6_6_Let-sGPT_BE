package com.openat.search.product.application.dto;

import com.openat.search.product.domain.model.Category;
import com.openat.search.product.domain.model.Product;
import java.time.Instant;
import java.util.UUID;

public record ProductInfo(
    UUID id,
    String sellerName,
    String name,
    String description,
    UUID categoryId,
    String categoryName,
    Long price,
    String thumbnailKey,
    Instant createdAt) {

  public static ProductInfo from(Product product, String sellerName) {
    Category category = product.getCategory();
    return new ProductInfo(
        product.getId(),
        sellerName,
        product.getName(),
        product.getDescription(),
        category != null ? category.getId() : null,
        category != null ? category.getName() : null,
        product.getPrice(),
        product.getThumbnailKey(),
        product.getCreatedAt());
  }
}
