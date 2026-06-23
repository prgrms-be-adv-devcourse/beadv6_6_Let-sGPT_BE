package com.openat.payment.presentation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record RefundHistoryResponse(
        @Schema(description = "환불 이력 목록") List<RefundResponse> content,
        @Schema(description = "전체 페이지 수") int totalPages) {
}
