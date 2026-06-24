package com.openat.payment.application.dto;

import java.util.UUID;

public record ChargePgCommand(UUID memberId, Long amount, String idempotencyKey) {
}
