package com.openat.order.presentation.controller;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.error.ErrorCode;
import com.openat.common.error.ErrorResponse;
import com.openat.order.application.port.DropNotFoundException;
import com.openat.order.application.port.ProductPortException;
import com.openat.order.domain.exception.OrderErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** 공통 catch-all보다 먼저 잡는다 — 놓치면 클라이언트 입력 오류와 상품 연동 실패가 500이 된다. */
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = OrderController.class)
public class OrderExceptionHandler {

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    return respond(CommonErrorCode.INVALID_INPUT);
  }

  @ExceptionHandler(DropNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleDropNotFound(DropNotFoundException e) {
    log.warn("[DropNotFound] {}", e.getMessage());
    return respond(OrderErrorCode.DROP_NOT_FOUND);
  }

  @ExceptionHandler(ProductPortException.class)
  public ResponseEntity<ErrorResponse> handleProductPortFailure(ProductPortException e) {
    log.error("[ProductPortException] failCode={}", e.getFailCode(), e);
    return respond(OrderErrorCode.PORT_ERROR);
  }

  private ResponseEntity<ErrorResponse> respond(ErrorCode errorCode) {
    return ResponseEntity.status(errorCode.getHttpStatus()).body(ErrorResponse.of(errorCode));
  }
}
