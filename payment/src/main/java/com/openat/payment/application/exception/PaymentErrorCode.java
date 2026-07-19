package com.openat.payment.application.exception;

import com.openat.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PaymentErrorCode implements ErrorCode {
  INSUFFICIENT_BALANCE(HttpStatus.CONFLICT, "INSUFFICIENT_BALANCE", "지갑 잔액이 부족합니다."),
  IDEMPOTENCY_KEY_CONFLICT(
      HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT", "동일한 멱등키로 다른 내용의 요청이 이미 처리되었습니다."),
  ORDER_VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "ORDER_VALIDATION_FAILED", "주문 정보가 일치하지 않습니다."),
  FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "본인 소유의 리소스가 아닙니다."),
  EXCEED_REFUNDABLE_AMOUNT(HttpStatus.CONFLICT, "EXCEED_REFUNDABLE_AMOUNT", "환불 가능 금액을 초과했습니다."),
  INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT", "금액은 1 이상의 양수여야 합니다."),
  ALREADY_PROCESSED(HttpStatus.CONFLICT, "ALREADY_PROCESSED", "이미 종결된 결제이며 다른 paymentKey로 재confirm이 시도되었습니다."),
  PAYMENT_ATTEMPT_IN_PROGRESS(
      HttpStatus.CONFLICT, "PAYMENT_ATTEMPT_IN_PROGRESS", "동일 주문의 다른 결제 시도가 진행 중입니다."),
  PG_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE,
      "PG_UNAVAILABLE",
      "결제 대행사가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해주세요."),
  ORDER_SERVICE_UNAVAILABLE(
      HttpStatus.SERVICE_UNAVAILABLE,
      "ORDER_SERVICE_UNAVAILABLE",
      "주문 서비스가 일시적으로 응답하지 않습니다. 잠시 후 다시 시도해주세요.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String message;
}
