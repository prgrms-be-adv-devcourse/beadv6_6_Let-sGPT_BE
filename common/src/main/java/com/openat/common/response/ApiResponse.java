package com.openat.common.response;

import org.springframework.http.HttpStatus;

/**
 * 모든 API 성공 응답의 공통 래퍼.
 *
 * <p>합의 포맷: {@code { "data": <본문>, "status": <HTTP 상태코드> }}
 *
 * <pre>
 * return ApiResponse.ok(orderResponse);          // 200
 * return ApiResponse.of(orderResponse, 201);     // 생성
 * </pre>
 *
 * @param data   응답 본문 (없으면 {@code null})
 * @param status HTTP 상태코드
 */
public record ApiResponse<T>(T data, int status) {

    /** 200 OK */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, HttpStatus.OK.value());
    }

    /** 임의 상태코드 (예: 201 Created) */
    public static <T> ApiResponse<T> of(T data, int status) {
        return new ApiResponse<>(data, status);
    }

    /** 임의 상태코드 (HttpStatus 버전) */
    public static <T> ApiResponse<T> of(T data, HttpStatus status) {
        return new ApiResponse<>(data, status.value());
    }
}
