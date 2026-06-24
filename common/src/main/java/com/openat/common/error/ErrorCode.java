package com.openat.common.error;

import org.springframework.http.HttpStatus;

/**
 * 도메인별 에러코드가 구현하는 공통 계약.
 *
 * <p>합의: "도메인별 error enum". 각 도메인은 이 인터페이스를 구현하는 enum을 정의한다.
 *
 * <pre>
 * &#64;Getter
 * &#64;RequiredArgsConstructor
 * public enum ProductErrorCode implements ErrorCode {
 *     SOLD_OUT(HttpStatus.CONFLICT, "SOLD_OUT", "재고가 없습니다."),
 *     NOT_OPEN(HttpStatus.BAD_REQUEST, "NOT_OPEN", "아직 오픈 전입니다."),
 *     LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "LIMIT_EXCEEDED", "1인 구매 한도를 초과했습니다.");
 *
 *     private final HttpStatus httpStatus;
 *     private final String code;
 *     private final String message;
 * }
 * </pre>
 */
public interface ErrorCode {

    /** 클라이언트에 노출되는 에러 식별 코드 (예: {@code "SOLD_OUT"}) */
    String getCode();

    /** 기본 에러 메시지 */
    String getMessage();

    /** 매핑되는 HTTP 상태코드 */
    HttpStatus getHttpStatus();
}
