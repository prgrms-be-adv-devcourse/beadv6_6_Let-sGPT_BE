package com.openat.payment.presentation.dto;

import java.util.UUID;

public record RefundRequest(UUID paymentId, Long amount, String reason) {
}
