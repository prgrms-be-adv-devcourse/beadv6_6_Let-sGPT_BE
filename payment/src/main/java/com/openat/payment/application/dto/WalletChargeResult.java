package com.openat.payment.application.dto;

import java.util.UUID;

public record WalletChargeResult(UUID chargeId, String status, Long amount) {
}
