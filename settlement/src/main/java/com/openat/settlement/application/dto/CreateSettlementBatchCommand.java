package com.openat.settlement.application.dto;

import com.openat.settlement.domain.model.SettlementBatchType;

/**
 * 정산 배치 생성 요청 DTO입니다.
 *
 * Command DTO는 "무엇을 실행할지"에 필요한 입력값을 담습니다.
 */
public record CreateSettlementBatchCommand(
        String settlementMonth,
        SettlementBatchType batchType
) {
}
