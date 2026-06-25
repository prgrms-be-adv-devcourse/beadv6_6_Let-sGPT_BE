package com.openat.product.presentation.dto;

import com.openat.product.application.dto.ProductInfo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
    @Schema(description = "상품 id") UUID id,
    @Schema(description = "판매자 id") UUID sellerId,
    @Schema(description = "상품명") String name,
    @Schema(description = "상품 설명") String description,
    @Schema(description = "카테고리 id, null: 미분류") UUID categoryId,
    @Schema(description = "카테고리명, null: 미분류") String categoryName,
    @Schema(description = "판매가, null: 가격 미정") Long price,
    @Schema(description = "썸네일 키") String thumbnailKey,
    @Schema(description = "생성 일시") Instant createdAt) {

  public static ProductResponse from(ProductInfo info) {
    return new ProductResponse(
        info.id(),
        info.sellerId(),
        info.name(),
        info.description(),
        info.categoryId(),
        info.categoryName(),
        info.price(),
        info.thumbnailKey(),
        info.createdAt());
  }
}
