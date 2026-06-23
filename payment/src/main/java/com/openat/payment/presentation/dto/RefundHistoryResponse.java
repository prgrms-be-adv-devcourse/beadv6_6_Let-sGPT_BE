package com.openat.payment.presentation.dto;

import java.util.List;

public record RefundHistoryResponse(List<RefundResponse> content, int totalPages) {
}
