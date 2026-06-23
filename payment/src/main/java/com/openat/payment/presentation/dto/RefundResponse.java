package com.openat.payment.presentation.dto;

import java.util.UUID;

public record RefundResponse(UUID refundId, UUID paymentId, Long amount, String status) {
}
