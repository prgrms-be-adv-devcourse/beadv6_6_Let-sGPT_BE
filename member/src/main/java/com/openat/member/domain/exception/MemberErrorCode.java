package com.openat.member.domain.exception;

import com.openat.common.error.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum MemberErrorCode implements ErrorCode {

    MEMBER_DUPLICATE_EMAIL(HttpStatus.CONFLICT, "MEMBER_DUPLICATE_EMAIL", "이미 사용 중인 이메일입니다."),
    MEMBER_DUPLICATE_NICKNAME(HttpStatus.CONFLICT, "MEMBER_DUPLICATE_NICKNAME", "이미 사용 중인 닉네임입니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND", "회원을 찾을 수 없습니다."),
    MEMBER_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "MEMBER_INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."),
    MEMBER_INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "MEMBER_INVALID_REFRESH_TOKEN", "유효하지 않은 리프레시 토큰입니다."),
    MEMBER_WITHDRAWN(HttpStatus.FORBIDDEN, "MEMBER_WITHDRAWN", "탈퇴한 계정입니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
