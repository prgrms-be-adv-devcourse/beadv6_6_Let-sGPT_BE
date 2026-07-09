package com.openat.search.product.presentation.dto;

import com.openat.search.product.domain.repository.ProductSearchCondition;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record ProductSearchRequest(
    @Schema(description = "Category id") UUID categoryId,
    @Schema(description = "Product name keyword") String keyword) {

  public ProductSearchCondition toCondition() {
    return new ProductSearchCondition(categoryId, keyword, null);
  }

  public ProductSearchCondition toCondition(UUID sellerId) {
    return new ProductSearchCondition(categoryId, keyword, sellerId);
  }
}
