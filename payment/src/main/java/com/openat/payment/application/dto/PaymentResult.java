package com.openat.payment.application.dto;

import java.util.UUID;

public record PaymentResult(UUID paymentId, String status, Long amount, String pgPaymentKey) {

    public static PaymentResult of(UUID paymentId, String status, Long amount) {
        return new PaymentResult(paymentId, status, amount, null);
    }
}
