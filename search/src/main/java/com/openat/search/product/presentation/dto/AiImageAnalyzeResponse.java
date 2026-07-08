package com.openat.search.product.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "AI 이미지 분석 응답")
public record AiImageAnalyzeResponse(
    @Schema(description = "사용한 프롬프트") String prompt,
    @Schema(description = "분석 결과") String answer) {}
