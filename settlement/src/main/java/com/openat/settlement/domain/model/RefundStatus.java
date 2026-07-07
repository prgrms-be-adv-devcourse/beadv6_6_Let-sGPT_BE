package com.openat.settlement.domain.model;

public enum RefundStatus {

    // 환불이 최종 완료된 상태
    // 정산 서비스에서는 COMPLETED 상태의 환불 건만 저장 대상으로 사용
    COMPLETED
}
