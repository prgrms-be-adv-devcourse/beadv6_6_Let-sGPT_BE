package com.openat.common.error;

/**
 * 모든 API 에러 응답의 공통 포맷.
 *
 * <p>합의 포맷: {@code { "error": "CODE", "message": "..." }}
 * (HTTP 상태코드는 응답 자체의 status로 전달한다.)
 *
 * @param error   에러 식별 코드 (예: {@code "SOLD_OUT"})
 * @param message 에러 메시지
 */
public record ErrorResponse(String error, String message) {

    public static ErrorResponse of(ErrorCode errorCode) {
        return new ErrorResponse(errorCode.getCode(), errorCode.getMessage());
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(errorCode.getCode(), message);
    }
}
