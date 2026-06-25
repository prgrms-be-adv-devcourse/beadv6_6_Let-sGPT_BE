package com.openat.payment.application.client;

// 토스 결제조회 API(GET /v1/payments/{paymentKey}) 응답 — TTL 스캐너(§3)가 confirm 미호출 건을 강제 확정할 때 사용.
public record TossQueryResult(Status status, String pgTxId) {

    public enum Status {
        APPROVED, FAILED, NOT_FOUND
    }

    public static TossQueryResult of(Status status, String pgTxId) {
        return new TossQueryResult(status, pgTxId);
    }
}
