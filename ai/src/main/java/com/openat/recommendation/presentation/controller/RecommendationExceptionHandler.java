package com.openat.recommendation.presentation.controller;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.error.ErrorResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 잘못된 형식의 요청 파라미터(예: productId가 UUID가 아님)를 400으로 매핑한다.
 *
 * <p>공통 {@code GlobalExceptionHandler}에는 타입 불일치 전용 핸들러가 없어, 그대로 두면
 * catch-all({@code Exception} → 500)에 잡혀 클라이언트 입력 오류가 500으로 응답된다. 이 어드바이스를
 * 최우선순위로 두고 이 컨트롤러에만 적용해, 타입 불일치가 catch-all보다 먼저 400으로 처리되게 한다.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = RecommendationController.class)
public class RecommendationExceptionHandler {

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return ResponseEntity.status(CommonErrorCode.INVALID_INPUT.getHttpStatus())
        .body(ErrorResponse.of(CommonErrorCode.INVALID_INPUT));
  }
}
