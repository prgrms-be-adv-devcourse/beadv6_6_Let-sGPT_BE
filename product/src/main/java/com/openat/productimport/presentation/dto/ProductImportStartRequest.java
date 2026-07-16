package com.openat.productimport.presentation.dto;

import com.openat.productimport.domain.model.ProductImportSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProductImportStartRequest(
    @Schema(description = "원본 유형", example = "LOCAL") @NotNull ProductImportSourceType sourceType,
    @Schema(
            description = "서버가 접근할 수 있는 로컬 폴더 또는 S3 prefix",
            example = "C:\\Users\\demo\\.openat\\product-imports\\limited-products-1000")
        @NotBlank
        @Size(max = 2048)
        String location,
    @Schema(description = "검증만 수행하고 실제 등록은 생략", example = "true") Boolean dryRun) {

  public boolean isDryRun() {
    return Boolean.TRUE.equals(dryRun);
  }
}
