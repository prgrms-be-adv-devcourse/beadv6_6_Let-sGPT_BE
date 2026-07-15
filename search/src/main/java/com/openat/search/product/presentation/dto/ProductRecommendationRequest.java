package com.openat.search.product.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ProductRecommendationRequest(
    @NotBlank
        @Schema(
            description = "파이프(|)로 구분한 상품 ID 목록",
            example =
                "20ae1988-24ec-3c83-8d5b-e973aeab350d|f5bbf8d7-0d08-3abf-ae30-23402c7137f7|e12487e1-045d-3c48-8805-fdc3a9f3f5fd")
        String id,
    @NotBlank @Schema(description = "파이프(|)로 구분한 임베딩 가중치 목록", example = "0.5|0.4|0.2") String score,
    @NotBlank @Schema(description = "파이프(|)로 구분한 구매 여부 목록(T: 구매, F: 미구매)", example = "T|F|T")
        String buy,
    @Min(1) @Max(100) @Schema(description = "추천 상품 개수", example = "20", defaultValue = "20")
        Integer size) {}
