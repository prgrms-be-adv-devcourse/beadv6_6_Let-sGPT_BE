package com.openat.search.product.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ProductRecommendationRequest(
    @NotBlank
        @Schema(
            description = "Pipe-separated product ids",
            example =
                "20ae1988-24ec-3c83-8d5b-e973aeab350d|f5bbf8d7-0d08-3abf-ae30-23402c7137f7|e12487e1-045d-3c48-8805-fdc3a9f3f5fd")
        String id,
    @NotBlank @Schema(description = "Pipe-separated embedding weights", example = "0.5|0.4|0.2")
        String score,
    @NotBlank @Schema(description = "Pipe-separated purchase flags", example = "T|F|T") String buy,
    @Min(1)
        @Max(100)
        @Schema(description = "Number of recommended products", example = "20", defaultValue = "20")
        Integer size) {}
