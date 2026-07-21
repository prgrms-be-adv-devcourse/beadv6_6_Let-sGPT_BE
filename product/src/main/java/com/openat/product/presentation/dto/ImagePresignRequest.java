package com.openat.product.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

public record ImagePresignRequest(
    @Schema(description = "이미지 콘텐츠 타입") @NotBlank String contentType) {}
