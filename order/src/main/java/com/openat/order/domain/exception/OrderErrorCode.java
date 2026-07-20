package com.openat.order.domain.exception;

import com.openat.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
  NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."),
  NOT_OWNER(HttpStatus.FORBIDDEN, "ORDER_NOT_OWNER", "주문에 접근할 수 있는 권한이 없습니다."),
  INVALID_INPUT(HttpStatus.BAD_REQUEST, "ORDER_INVALID_INPUT", "요청 데이터가 유효하지 않습니다."),
  IDEMPOTENCY_CONFLICT(
      HttpStatus.CONFLICT, "ORDER_IDEMPOTENCY_CONFLICT", "같은 멱등키로 다른 주문 요청을 처리할 수 없습니다."),
  ALREADY_COMPLETED(
      HttpStatus.CONFLICT,
      "ORDER_ALREADY_COMPLETED",
      "결제 완료 주문은 취소할 수 없습니다. /api/v1/orders/{orderId}/refund-requests를 이용해 주세요."),
  INVALID_STATUS(HttpStatus.CONFLICT, "ORDER_INVALID_STATUS", "요청한 주문 상태에서는 처리할 수 없습니다."),
  SOLD_OUT(HttpStatus.CONFLICT, "SOLD_OUT", "재고가 없습니다."),
  DROP_NOT_OPEN(HttpStatus.BAD_REQUEST, "DROP_NOT_OPEN", "드롭이 오픈되지 않았습니다."),
  DROP_CLOSED(HttpStatus.CONFLICT, "DROP_CLOSED", "종료된 드롭입니다."),
  LIMIT_EXCEEDED(HttpStatus.CONFLICT, "LIMIT_EXCEEDED", "구매 가능 수량을 초과했습니다."),
  PAYMENT_IN_PROGRESS(HttpStatus.CONFLICT, "ORDER_PAYMENT_IN_PROGRESS", "결제 확인 중입니다. 잠시 후 다시 시도해주세요."),
  PORT_ERROR(HttpStatus.BAD_GATEWAY, "ORDER_EXTERNAL_API_ERROR", "외부 주문 연동 API 처리에 실패했습니다.");

  private final HttpStatus httpStatus;
  private final String code;
  private final String message;
}
