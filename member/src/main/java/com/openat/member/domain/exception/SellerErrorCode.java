package com.openat.member.domain.exception;

import com.openat.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SellerErrorCode implements ErrorCode {

    SELLER_INFO_NOT_FOUND(HttpStatus.NOT_FOUND, "SELLER_INFO_NOT_FOUND", "등록된 판매자 정보를 찾을 수 없습니다."),
    SELLER_TOKEN_EXCHANGE_INVALID_SUBJECT_TOKEN(HttpStatus.UNAUTHORIZED, "SELLER_TOKEN_EXCHANGE_INVALID_SUBJECT_TOKEN", "유효하지 않은 subject_token입니다."),
    SELLER_TOKEN_EXCHANGE_UNAUTHORIZED_SELLER(HttpStatus.FORBIDDEN, "SELLER_TOKEN_EXCHANGE_UNAUTHORIZED_SELLER", "해당 판매자 정보에 대한 권한이 없거나 탈퇴한 판매자입니다."),
    SELLER_TOKEN_EXCHANGE_UNSUPPORTED_SCOPE(HttpStatus.BAD_REQUEST, "SELLER_TOKEN_EXCHANGE_UNSUPPORTED_SCOPE", "지원하지 않는 scope입니다."),
    SELLER_TOKEN_EXCHANGE_UNSUPPORTED_AUDIENCE(HttpStatus.BAD_REQUEST, "SELLER_TOKEN_EXCHANGE_UNSUPPORTED_AUDIENCE", "지원하지 않는 audience입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
