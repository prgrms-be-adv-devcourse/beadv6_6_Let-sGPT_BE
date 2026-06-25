package com.openat.payment.application.client;

// 토스 결제취소(환불) API 응답 — 환불 PG 호출은 원래도 동기 응답 구조(A16 영향 없음).
// UNKNOWN(타임아웃 등 응답 불확실)은 보정하지 않고 PENDING으로 남겨, 보조 웹훅이 나중에 확정하게 한다.
public record TossRefundResult(Status status, String pgRefundKey, String reason) {

    public enum Status {
        COMPLETE, FAILED, UNKNOWN
    }

    public static TossRefundResult complete(String pgRefundKey) {
        return new TossRefundResult(Status.COMPLETE, pgRefundKey, null);
    }

    public static TossRefundResult failed(String reason) {
        return new TossRefundResult(Status.FAILED, null, reason);
    }

    public static TossRefundResult unknown() {
        return new TossRefundResult(Status.UNKNOWN, null, null);
    }
}
