package com.openat.productimport.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProductImportRow(
    int rowNumber,
    String externalId,
    String name,
    String description,
    UUID categoryId,
    String categoryName,
    long price,
    String thumbnailFile,
    List<String> imageFiles,
    Long dropPrice,
    Integer totalQuantity,
    Integer limitPerUser,
    Instant openAt,
    Instant closeAt) {

  public boolean hasDrop() {
    return totalQuantity != null;
  }

  public ProductImportRow withCategoryId(UUID resolvedCategoryId) {
    return new ProductImportRow(
        rowNumber,
        externalId,
        name,
        description,
        resolvedCategoryId,
        categoryName,
        price,
        thumbnailFile,
        imageFiles,
        dropPrice,
        totalQuantity,
        limitPerUser,
        openAt,
        closeAt);
  }
}
