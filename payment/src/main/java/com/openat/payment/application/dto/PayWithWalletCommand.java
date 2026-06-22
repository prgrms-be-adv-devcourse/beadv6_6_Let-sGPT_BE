package com.openat.payment.application.dto;

import java.util.UUID;

public record PayWithWalletCommand(UUID orderId, UUID memberId, Long amount, String idempotencyKey) {
}
