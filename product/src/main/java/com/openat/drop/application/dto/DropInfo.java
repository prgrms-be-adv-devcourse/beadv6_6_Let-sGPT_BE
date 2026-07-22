package com.openat.drop.application.dto;

import com.openat.category.domain.model.Category;
import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.product.domain.model.Product;
import java.time.Instant;
import java.util.UUID;

public record DropInfo(
    UUID id,
    UUID productId,
    String productName,
    String sellerName,
    UUID categoryId,
    String categoryName,
    String thumbnailKey,
    long dropPrice,
    int totalQuantity,
    int remainingQuantity,
    DropStatus status,
    Instant openAt,
    Instant closeAt,
    Integer limitPerUser) {

  public static DropInfo of(
      Drop drop, String sellerName, int remainingQuantity, DropStatus status) {
    Product product = drop.getProduct();
    Category category = product.getCategory();
    UUID categoryId = null;
    String categoryName = null;
    if (category != null) {
      categoryId = category.getId();
      categoryName = category.getName();
    }
    return new DropInfo(
        drop.getId(),
        product.getId(),
        product.getName(),
        sellerName,
        categoryId,
        categoryName,
        product.getThumbnailKey(),
        drop.getDropPrice(),
        drop.getTotalQuantity(),
        remainingQuantity,
        status,
        drop.getOpenAt(),
        drop.getCloseAt(),
        drop.getLimitPerUser());
  }
}
