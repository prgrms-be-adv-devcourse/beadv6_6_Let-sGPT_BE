package com.openat.payment.presentation.dto;

import java.util.UUID;

public record PaymentRequest(UUID orderId, Long amount, String method) {
}
