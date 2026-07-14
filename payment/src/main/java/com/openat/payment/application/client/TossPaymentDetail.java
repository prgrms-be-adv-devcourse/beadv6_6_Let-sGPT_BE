package com.openat.payment.application.client;

// PG 대사(WS-0) 전용 — 토스 결제조회 응답에서 금액(totalAmount)까지 포함해 반환한다.
// queryPaymentStatus(TTL 스캐너용, 상태만)와 의도적으로 분리 — 대사는 상태뿐 아니라 금액 일치까지 검증해야 한다.
// totalAmount가 null이면(스텁 등, 실제 PG에 물어볼 수 없는 환경) 호출측은 상태만으로 판정한다.
public record TossPaymentDetail(Status status, Long totalAmount, String pgTxId) {

    public enum Status {
        APPROVED, FAILED, NOT_FOUND
    }

    public static TossPaymentDetail of(Status status, Long totalAmount, String pgTxId) {
        return new TossPaymentDetail(status, totalAmount, pgTxId);
    }
}
