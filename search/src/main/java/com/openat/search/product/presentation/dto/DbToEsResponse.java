package com.openat.search.product.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record DbToEsResponse(
    @Schema(description = "Spring Batch job execution id") Long jobExecutionId,
    @Schema(description = "Batch status") String status,
    @Schema(description = "Exit status") String exitStatus) {}
