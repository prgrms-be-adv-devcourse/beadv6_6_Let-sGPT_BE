package com.openat.payment.application.client;

import java.util.UUID;

public record OrderValidationResult(UUID memberId, Long amount, String status, boolean valid) {
}
