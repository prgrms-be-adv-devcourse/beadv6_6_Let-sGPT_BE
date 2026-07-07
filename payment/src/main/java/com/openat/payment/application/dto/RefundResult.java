package com.openat.payment.application.dto;

import java.util.UUID;

public record RefundResult(UUID refundId, UUID paymentId, Long amount, String status) {
}
