package com.openat.search.product.presentation.dto;

import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record ProductRecommendationResponse(
    @Schema(description = "Product id") UUID id,
    @Schema(description = "Product name") String name,
    @Schema(description = "Product description") String description,
    @Schema(description = "AI image description indexed in Elasticsearch") String imgDescription) {

  public static ProductRecommendationResponse from(ProductDocument document) {
    return new ProductRecommendationResponse(
        UUID.fromString(document.id()),
        document.name(),
        document.description(),
        document.imgDescription());
  }
}
