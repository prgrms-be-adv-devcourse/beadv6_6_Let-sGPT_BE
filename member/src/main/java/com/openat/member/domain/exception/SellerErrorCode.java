package com.openat.member.domain.exception;

import com.openat.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SellerErrorCode implements ErrorCode {

    SELLER_INFO_NOT_FOUND(HttpStatus.NOT_FOUND, "SELLER_INFO_NOT_FOUND", "등록된 판매자 정보를 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
