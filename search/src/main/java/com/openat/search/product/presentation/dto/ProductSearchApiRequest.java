package com.openat.search.product.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;

public record ProductSearchApiRequest(
    @Schema(description = "Natural language vector search query", example = "가볍게 들고 다니는 미니 가방")
        String query,
    @Schema(description = "Category name keyword", example = "전자기기") String categoryName,
    @Schema(description = "Minimum price", example = "10000") Long startPrice,
    @Schema(description = "Maximum price", example = "50000") Long endPrice,
    @Schema(description = "Zero-based page number", example = "0") Integer page,
    @Schema(description = "Page size", example = "20") Integer size,
    @Schema(
            name = "sort",
            description =
                "Product sort condition (latest: createdAt,desc / lowest price: price,asc / highest price: price,desc)",
            example = "createdAt,desc",
            allowableValues = {"createdAt,desc", "price,asc", "price,desc"})
        @Pattern(
            regexp = "createdAt,desc|price,asc|price,desc",
            message = "sort must be one of createdAt,desc, price,asc, price,desc")
        String sort) {}
