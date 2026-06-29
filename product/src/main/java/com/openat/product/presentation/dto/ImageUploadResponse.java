package com.openat.product.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record ImageUploadResponse(
    @Schema(description = "저장된 이미지 키, 상품 thumbnailKey·imageKeys 로 사용") String key,
    @Schema(description = "이미지 조회 경로") String url) {}
