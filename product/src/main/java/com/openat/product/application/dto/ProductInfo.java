package com.openat.product.application.dto;

import com.openat.category.domain.model.Category;
import com.openat.product.domain.model.Product;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductInfo(
    UUID id,
    UUID sellerId,
    String sellerName,
    String name,
    String description,
    UUID categoryId,
    String categoryName,
    Long price,
    String thumbnailKey,
    List<String> imageKeys,
    Instant createdAt) {

  public static ProductInfo from(Product product, String sellerName) {
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
        sellerName,
        product.getName(),
        product.getDescription(),
        categoryId,
        categoryName,
        product.getPrice(),
        product.getThumbnailKey(),
        List.copyOf(product.getImageKeys()),
        product.getCreatedAt());
  }
}
