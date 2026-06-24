package com.openat.settlement.domain.exception;

import com.openat.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SettlementErrorCode implements ErrorCode {

    KAFKA_CONSUME_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "KAFKA_CONSUME_FAILED",
            "결제 이벤트 처리 중 오류가 발생했습니다."
    ),
    KAFKA_UNKNOWN_EVENT_TYPE(
            HttpStatus.BAD_REQUEST,
            "KAFKA_UNKNOWN_EVENT_TYPE",
            "지원하지 않는 결제 이벤트 타입입니다."
    ),

    BATCH_MONTHLY_LAUNCH_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "BATCH_MONTHLY_LAUNCH_FAILED",
            "월 정산 배치 실행 중 오류가 발생했습니다."
    ),
    BATCH_LOCAL_MONTHLY_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "BATCH_LOCAL_MONTHLY_FAILED",
            "로컬 월 정산 배치 실행 중 오류가 발생했습니다."
    ),
    BATCH_SETTLEMENT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "BATCH_SETTLEMENT_NOT_FOUND",
            "정산 배치 정보를 찾을 수 없습니다."
    ),

    SELLER_SETTLEMENT_FAILED(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "SELLER_SETTLEMENT_FAILED",
            "판매자 정산 처리 중 오류가 발생했습니다."
    ),
    INVALID_PAGE_REQUEST(
            HttpStatus.BAD_REQUEST,
            "INVALID_PAGE_REQUEST",
            "페이징 요청 값이 올바르지 않습니다."
    );

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
