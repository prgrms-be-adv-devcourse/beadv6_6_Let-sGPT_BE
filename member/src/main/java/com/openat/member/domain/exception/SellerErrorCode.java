package com.openat.member.domain.exception;

import com.openat.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum SellerErrorCode implements ErrorCode {

    // 팀 컨벤션: 모든 도메인 에러코드는 "도메인명_에러코드" 형식 → seller 관련은 전부 "SELLER_" 접두.
    SELLER_INFO_ALREADY_EXISTS(HttpStatus.CONFLICT, "SELLER_INFO_ALREADY_EXISTS", "이미 등록된 판매자 정보가 있습니다."),
    SELLER_INFO_NOT_FOUND(HttpStatus.NOT_FOUND, "SELLER_INFO_NOT_FOUND", "등록된 판매자 정보를 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
