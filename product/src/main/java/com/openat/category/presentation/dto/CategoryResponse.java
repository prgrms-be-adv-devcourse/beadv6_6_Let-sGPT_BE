package com.openat.category.presentation.dto;

import com.openat.category.domain.model.Category;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public record CategoryResponse(
    @Schema(description = "카테고리 id") UUID id, @Schema(description = "카테고리명") String name) {

  public static CategoryResponse from(Category category) {
    return new CategoryResponse(category.getId(), category.getName());
  }
}
