package com.openat.settlement.application.dto;

public record RetryFailedSellerSettlementsCommand(
        String settlementMonth
) {
}
