package com.openat.common.exception;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.error.ErrorCode;
import com.openat.common.error.ErrorResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 모든 도메인이 공유하는 전역 예외 처리기.
 *
 * <p>합의: {@code @RestControllerAdvice}로 예외를 한 곳에서 잡아 공통 에러 포맷
 * ({@link ErrorResponse})으로 변환. HTTP 상태코드는 {@link ErrorCode}에 매핑된 값을 사용한다.
 *
 * <p>각 도메인의 {@code Application} 컴포넌트 스캔 범위와 무관하게 동작하도록
 * 자동설정({@code CommonWebAutoConfiguration})으로 등록한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 도메인 비즈니스 예외 → 매핑된 상태코드 + 에러코드 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("[BusinessException] {} : {}", errorCode.getCode(), e.getMessage());
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ErrorResponse.of(errorCode, e.getMessage()));
    }

    /** 요청 본문 검증 실패(@Valid) → 400 INVALID_INPUT, 첫 필드 오류를 메시지로 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .orElse(CommonErrorCode.INVALID_INPUT.getMessage());
        return ResponseEntity
                .status(CommonErrorCode.INVALID_INPUT.getHttpStatus())
                .body(ErrorResponse.of(CommonErrorCode.INVALID_INPUT, message));
    }

    // 결제 도메인의 Idempotency-Key/X-User-Id 같은 필수 @RequestHeader가 빠지면 이 예외가 던져지는데,
    // 별도 핸들러가 없으면 클라이언트 실수(헤더 누락)인데도 500으로 응답해서 추가함.
    /** 필수 요청 헤더 누락(@RequestHeader) → 400 INVALID_INPUT, 누락된 헤더 이름을 메시지에 포함 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        String message = "필수 헤더가 없습니다: " + e.getHeaderName();
        return ResponseEntity
                .status(CommonErrorCode.INVALID_INPUT.getHttpStatus())
                .body(ErrorResponse.of(CommonErrorCode.INVALID_INPUT, message));
    }

    /** 메서드 파라미터/PathVariable 검증 실패(@Validated) → 400 INVALID_INPUT, 첫 위반사항을 메시지로 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .orElse(CommonErrorCode.INVALID_INPUT.getMessage());
        return ResponseEntity
                .status(CommonErrorCode.INVALID_INPUT.getHttpStatus())
                .body(ErrorResponse.of(CommonErrorCode.INVALID_INPUT, message));
    }

    /** 필수 쿼리 파라미터 누락 → 400 INVALID_INPUT */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
        String message = "%s 파라미터가 필요합니다.".formatted(e.getParameterName());
        return ResponseEntity
                .status(CommonErrorCode.INVALID_INPUT.getHttpStatus())
                .body(ErrorResponse.of(CommonErrorCode.INVALID_INPUT, message));
    }

    /** 요청 본문이 비어 있거나 JSON 형식이 잘못된 경우 → 400 INVALID_INPUT */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("[HttpMessageNotReadableException] {}", e.getMessage());
        return ResponseEntity
                .status(CommonErrorCode.INVALID_INPUT.getHttpStatus())
                .body(ErrorResponse.of(CommonErrorCode.INVALID_INPUT));
    }

    /** 미지원 HTTP 메서드 → 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity
                .status(CommonErrorCode.METHOD_NOT_ALLOWED.getHttpStatus())
                .body(ErrorResponse.of(CommonErrorCode.METHOD_NOT_ALLOWED));
    }

    /** 처리되지 않은 예외 → 500 (원인은 로그로만 남기고 내부 정보는 노출하지 않음) */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e) {
        log.error("[UnhandledException]", e);
        return ResponseEntity
                .status(CommonErrorCode.INTERNAL_ERROR.getHttpStatus())
                .body(ErrorResponse.of(CommonErrorCode.INTERNAL_ERROR));
    }
}
