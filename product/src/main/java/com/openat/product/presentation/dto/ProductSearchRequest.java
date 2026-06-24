package com.openat.product.presentation.dto;

import com.openat.product.domain.repository.ProductSearchCondition;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record ProductSearchRequest(
    @Schema(description = "카테고리 id, null: 전체") UUID categoryId,
    @Schema(description = "상품명 검색어, null: 전체", example = "스니커즈") String keyword) {

  public ProductSearchCondition toCondition() {
    return new ProductSearchCondition(categoryId, keyword);
  }
}
