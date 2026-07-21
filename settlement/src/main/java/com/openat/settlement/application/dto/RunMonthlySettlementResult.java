package com.openat.settlement.application.dto;

public record RunMonthlySettlementResult(
    Long jobExecutionId, String settlementMonth, String status) {}
