package com.openat.search.product.application.dto;

import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import java.time.Instant;
import java.util.Objects;
import java.util.stream.Stream;

public record ProductSearchSyncTestResponse(
    ProductSyncOperation operation,
    String id,
    String name,
    String description,
    String categoryId,
    String categoryName,
    String sellerName,
    Long price,
    String thumbnailKey,
    String imgDescription,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public enum ProductSyncOperation {
    INSERT,
    UPDATE,
    DELETE
  }

  public ProductDocument toDocument() {
    return new ProductDocument(
        id,
        name,
        description,
        categoryId,
        categoryName,
        sellerName,
        price,
        thumbnailKey,
        imgDescription,
        null,
        createdAt,
        updatedAt,
        deletedAt);
  }

  public Instant latestEventAt() {
    return Stream.of(createdAt, updatedAt, deletedAt)
        .filter(Objects::nonNull)
        .max(Instant::compareTo)
        .orElse(Instant.EPOCH);
  }
}
