package com.openat.payment.application.dto;

import java.util.List;

public record RefundHistoryResult(List<RefundResult> content, int totalPages) {
}
