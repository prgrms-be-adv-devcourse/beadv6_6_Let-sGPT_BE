package com.openat.recommendation.presentation.controller;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.error.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 타입 불일치를 400으로 매핑한다 — 공통 catch-all에 잡히면 클라이언트 입력 오류가 500이 된다. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = RecommendationController.class)
public class RecommendationExceptionHandler {

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch() {
    return ResponseEntity.status(CommonErrorCode.INVALID_INPUT.getHttpStatus())
        .body(ErrorResponse.of(CommonErrorCode.INVALID_INPUT));
  }
}
