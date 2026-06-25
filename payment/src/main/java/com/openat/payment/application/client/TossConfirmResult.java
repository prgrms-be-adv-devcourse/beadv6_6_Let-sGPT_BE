package com.openat.payment.application.client;

// 토스 결제 승인(POST /v1/payments/confirm) 응답 — 거절 사유는 일반 거절(PG_REJECTED)과<br/>
// 토스측 자체 만료(EXPIRED, 하자드#23)를 구분해서 reason에 담는다.
public record TossConfirmResult(boolean approved, String pgTxId, String reason) {

    public static TossConfirmResult approved(String pgTxId) {
        return new TossConfirmResult(true, pgTxId, null);
    }

    public static TossConfirmResult rejected(String reason) {
        return new TossConfirmResult(false, null, reason);
    }
}
