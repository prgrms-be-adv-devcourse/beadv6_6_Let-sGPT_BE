package com.openat.member.infrastructure.config;

import com.openat.common.error.CommonErrorCode;
import com.openat.common.error.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * member 모듈 전용 예외 처리기. common의 {@code GlobalExceptionHandler}(모든 모듈 공용)를
 * 보완한다 — common은 JPA/ORM 의존성을 안 갖도록 의도적으로 가볍게 유지하고 있어
 * ({@code org.springframework.dao.*}가 common의 클래스패스에 없음), member처럼 JPA를 쓰는
 * 모듈에서만 필요한 예외 처리는 각 모듈에 둔다.
 *
 * <p>낙관적 락(@Version) 충돌 → 409. {@code Member.restore()}(로그인 시 복구)와
 * {@code MemberAnonymizeService.anonymize()}(익명화 스케줄러)가 같은 회원 행을 동시에
 * 갱신하려 할 때, 나중에 커밋되는 쪽이 이 예외로 실패한다 — 서버 버그가 아니라 정상적인
 * 동시성 보호 결과이므로 warn으로만 남기고 클라이언트가 재시도하면 되는 409로 응답한다.
 */
@Slf4j
@RestControllerAdvice
public class MemberExceptionHandler {

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLockingFailure(OptimisticLockingFailureException e) {
        log.warn("[OptimisticLockingFailureException] {}", e.getMessage());
        return ResponseEntity
                .status(CommonErrorCode.CONFLICT.getHttpStatus())
                .body(ErrorResponse.of(CommonErrorCode.CONFLICT));
    }
}
