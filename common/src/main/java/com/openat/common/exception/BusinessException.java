package com.openat.common.exception;

import com.openat.common.error.ErrorCode;
import lombok.Getter;

/**
 * 모든 도메인 비즈니스 예외의 최상위 타입 (언체크).
 *
 * <p>합의: 커스텀 {@code BusinessException}(언체크) + 도메인별 error enum.
 * 도메인은 {@link ErrorCode}를 던지거나, 필요하면 별도 메시지를 덧붙인다.
 *
 * <pre>
 * throw new BusinessException(ProductErrorCode.SOLD_OUT);
 * throw new BusinessException(ProductErrorCode.SOLD_OUT, "드롭 #" + dropId + " 매진");
 * </pre>
 */
@Getter
public class BusinessException extends RuntimeException {

    private final transient ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
