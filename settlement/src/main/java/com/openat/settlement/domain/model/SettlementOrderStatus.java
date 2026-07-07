package com.openat.settlement.domain.model;

public enum SettlementOrderStatus {

    // 정산 대상 주문으로 준비된 상태
    READY,

    // 판매자 정산 결과에 반영되어 정산 완료된 상태
    COMPLETED
}
