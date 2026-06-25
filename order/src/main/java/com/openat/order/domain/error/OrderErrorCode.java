package com.openat.order.domain.error;

import com.openat.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum OrderErrorCode implements ErrorCode {
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다."),
    ORDER_FORBIDDEN(HttpStatus.FORBIDDEN, "ORDER_FORBIDDEN", "주문 접근 권한이 없습니다."),
    INVALID_ORDER_STATUS(HttpStatus.CONFLICT, "INVALID_ORDER_STATUS", "주문 상태가 유효하지 않습니다."),
    DUPLICATE_ORDER_REQUEST(HttpStatus.CONFLICT, "DUPLICATE_ORDER_REQUEST", "이미 처리된 주문 요청입니다."),
    SOLD_OUT(HttpStatus.CONFLICT, "SOLD_OUT", "재고가 없습니다."),
    NOT_OPEN(HttpStatus.BAD_REQUEST, "NOT_OPEN", "아직 오픈 전입니다."),
    LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "LIMIT_EXCEEDED", "1인 구매 한도를 초과했습니다."),
    PRODUCT_SNAPSHOT_FAILED(HttpStatus.BAD_GATEWAY, "PRODUCT_SNAPSHOT_FAILED", "주문 상품 정보를 조회하지 못했습니다."),
    STOCK_DECREASE_FAILED(HttpStatus.CONFLICT, "STOCK_DECREASE_FAILED", "재고 차감에 실패했습니다."),
    STOCK_RESTORE_FAILED(HttpStatus.BAD_GATEWAY, "STOCK_RESTORE_FAILED", "재고 복구 요청에 실패했습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
