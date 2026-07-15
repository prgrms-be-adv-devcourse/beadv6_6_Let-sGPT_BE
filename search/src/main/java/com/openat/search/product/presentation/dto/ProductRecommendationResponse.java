package com.openat.search.product.presentation.dto;

import com.openat.search.product.infrastructure.elasticsearch.ProductDocument;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record ProductRecommendationResponse(
    @Schema(description = "상품 ID") UUID id,
    @Schema(description = "상품명") String name,
    @Schema(description = "상품 설명") String description,
    @Schema(description = "Elasticsearch에 저장된 AI 이미지 설명") String imgDescription) {

  public static ProductRecommendationResponse from(ProductDocument document) {
    return new ProductRecommendationResponse(
        UUID.fromString(document.id()),
        document.name(),
        document.description(),
        document.imgDescription());
  }
}
