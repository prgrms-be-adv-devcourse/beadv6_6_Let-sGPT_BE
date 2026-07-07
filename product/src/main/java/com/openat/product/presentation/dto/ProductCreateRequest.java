package com.openat.product.presentation.dto;

import com.openat.product.application.dto.ProductCreateCommand;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record ProductCreateRequest(
    @Schema(description = "상품명", example = "한정판 스니커즈") @NotBlank @Size(max = 100) String name,
    @Schema(description = "상품 설명", example = "한정판으로 출시된 모델") String description,
    @Schema(description = "카테고리 식별자(미지정 시 미분류)") UUID categoryId,
    @Schema(description = "판매가(원)", example = "219000") @Positive Long price,
    @Schema(description = "썸네일 객체 키") @Size(max = 512) String thumbnailKey,
    @Schema(description = "추가 이미지 키 목록(갤러리)") List<String> imageKeys) {

  public ProductCreateCommand toCommand(UUID sellerId) {
    return new ProductCreateCommand(
        sellerId, name, description, categoryId, price, thumbnailKey, imageKeys);
  }
}
