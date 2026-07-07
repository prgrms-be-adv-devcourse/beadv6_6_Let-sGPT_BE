package com.openat.settlement.domain.model;

public enum SettlementBatchStatus {

    // 배치 실행 전 대기 상태
    READY,

    // 배치 실행 중 상태
    RUNNING,

    // 배치가 정상적으로 완료된 상태
    COMPLETED,

    // 배치 실행 중 오류가 발생하여 실패한 상태
    FAILED
}
