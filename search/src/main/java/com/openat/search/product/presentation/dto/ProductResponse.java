package com.openat.search.product.presentation.dto;

import com.openat.search.product.application.dto.ProductInfo;
import com.openat.search.product.application.dto.ProductSearchResult;
import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
    @Schema(description = "Product id") UUID id,
    @Schema(description = "Product name") String name,
    @Schema(description = "Product description") String description,
    @Schema(description = "Category name") String categoryName,
    @Schema(description = "Price") Long price,
    @Schema(description = "Thumbnail image key") String thumbnailKey,
    @Schema(description = "Created at") Instant createdAt,
    @Schema(description = "Elasticsearch search score") Float score) {

  public static ProductResponse from(ProductInfo info) {
    return new ProductResponse(
        info.id(),
        info.name(),
        info.description(),
        info.categoryName(),
        info.price(),
        info.thumbnailKey(),
        info.createdAt(),
        null);
  }

  public static ProductResponse from(ProductDocument document) {
    return from(document, null);
  }

  public static ProductResponse from(ProductSearchResult result) {
    return from(result.document(), result.score());
  }

  public static ProductResponse from(ProductDocument document, Float score) {
    return new ProductResponse(
        toUuid(document.id()),
        document.name(),
        document.description(),
        document.categoryName(),
        document.price(),
        document.thumbnailKey(),
        document.createdAt(),
        score);
  }

  private static UUID toUuid(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return UUID.fromString(value);
  }
}
