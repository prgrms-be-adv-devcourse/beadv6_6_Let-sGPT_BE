package com.openat.settlement.domain.model;

public enum RefundReflectedType {

    // 정산 완료 전 환불
    // settlement_order.refund_amount에 직접 반영
    BEFORE_SETTLEMENT,

    // 정산 완료 후 환불
    // settlement_adjustment에 보정 차감 건으로 생성
    AFTER_SETTLEMENT
}
