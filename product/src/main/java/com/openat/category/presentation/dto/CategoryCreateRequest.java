package com.openat.category.presentation.dto;

import com.openat.category.application.dto.CategoryCreateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CategoryCreateRequest(
    @Schema(description = "카테고리명", example = "의류") @NotBlank @Size(max = 50) String name) {

  public CategoryCreateCommand toCommand() {
    return new CategoryCreateCommand(name);
  }
}
