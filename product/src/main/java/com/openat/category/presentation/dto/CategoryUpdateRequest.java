package com.openat.category.presentation.dto;

import com.openat.category.application.dto.CategoryUpdateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CategoryUpdateRequest(
    @Schema(description = "카테고리명", example = "의류") @NotBlank @Size(max = 50) String name) {

  public CategoryUpdateCommand toCommand(UUID id) {
    return new CategoryUpdateCommand(id, name);
  }
}
