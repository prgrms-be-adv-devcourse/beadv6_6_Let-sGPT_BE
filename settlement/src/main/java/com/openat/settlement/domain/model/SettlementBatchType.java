package com.openat.settlement.domain.model;

public enum SettlementBatchType {

    // 결제 완료 데이터를 결제 서비스에서 가져와 정산 주문 테이블에 적재하는 배치
    LOAD_PAYMENT,

    // 환불 완료 데이터를 결제 서비스에서 가져와 정산 환불 테이블에 적재하는 배치
    LOAD_REFUND,

    // 판매자별 월 정산 금액을 계산하고 정산 결과를 생성하는 배치
    SETTLEMENT_RUN,

    SETTLEMENT_RETRY
}
