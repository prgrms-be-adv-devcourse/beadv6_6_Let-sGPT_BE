package com.openat.payment.presentation.dto;

import java.util.UUID;

public record WalletChargeResponse(UUID chargeId, String status) {
}
