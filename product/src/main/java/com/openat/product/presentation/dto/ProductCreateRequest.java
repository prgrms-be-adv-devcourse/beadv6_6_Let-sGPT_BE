package com.openat.product.presentation.dto;

import com.openat.product.application.dto.ProductCreateCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ProductCreateRequest(
    @NotBlank @Size(max = 100) String name,
    String description,
    @NotNull UUID categoryId,
    @Positive Long price,
    @Size(max = 512) String thumbnailKey) {

  public ProductCreateCommand toCommand(UUID sellerId) {
    return new ProductCreateCommand(sellerId, name, description, categoryId, price, thumbnailKey);
  }
}
