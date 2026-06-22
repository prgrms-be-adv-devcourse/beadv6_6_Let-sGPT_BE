package com.openat.payment.presentation.dto;

import java.util.UUID;

public record PaymentResponse(UUID paymentId, String status, String paymentKey) {

    public static PaymentResponse of(UUID paymentId, String status) {
        return new PaymentResponse(paymentId, status, null);
    }
}
