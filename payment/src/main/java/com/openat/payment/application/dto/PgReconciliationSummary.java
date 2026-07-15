package com.openat.payment.application.dto;

// PG 대사 배치(WS-0) 실행 결과 — 수동 트리거 API 응답 + 스케줄러 로그에 공용으로 사용.
public record PgReconciliationSummary(
        String businessDate,
        int paymentMatched,
        int paymentMismatched,
        int paymentSkipped,
        int refundMatched,
        int refundMismatched,
        int refundSkipped) {
}
