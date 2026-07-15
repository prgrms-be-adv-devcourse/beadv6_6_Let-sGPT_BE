package com.openat.settlement.application.dto;

// 정산 대사(WS-3) 실행 결과 — 수동 트리거 API 응답 + 스케줄러 로그 공용.
public record DailyReconciliationSummary(String businessDate, String status, int paymentCount, int refundCount, int discrepancyCount) {
}
