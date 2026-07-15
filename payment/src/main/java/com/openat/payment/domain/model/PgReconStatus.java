package com.openat.payment.domain.model;

// PG 대사(payment DB ↔ 토스) 검증 상태 — 정산 대사 일별 API는 MATCHED 행만 노출한다(WS-0).
// WALLET 결제/환불은 PG 호출 자체가 없어 생성 시점에 바로 MATCHED로 확정한다.
public enum PgReconStatus {
    NOT_CHECKED, MATCHED, MISMATCH
}
