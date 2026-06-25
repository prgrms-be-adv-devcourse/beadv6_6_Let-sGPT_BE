package com.openat.payment.application.dto;

import java.util.UUID;

public record ChargeWalletCommand(UUID memberId, Long amount, String idempotencyKey) {
}
