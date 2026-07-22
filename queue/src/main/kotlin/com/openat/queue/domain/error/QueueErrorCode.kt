package com.openat.queue.domain.error

import com.openat.common.error.ErrorCode
import org.springframework.http.HttpStatus

/**
 * queue 도메인 에러코드 - common의 [ErrorCode] 계약을 구현하는 도메인별 enum 합의를 따른다.
 * `BusinessException(QueueErrorCode.X)`로 던지면 common의 GlobalExceptionHandler(자동설정)가
 * 매핑된 상태코드 + 공통 에러 포맷으로 변환한다.
 */
enum class QueueErrorCode(
    private val httpStatus: HttpStatus,
    private val code: String,
    private val message: String,
) : ErrorCode {

    /**
     * 진입 요청 수량이 허용 상한을 초과. 상한은 두 겹이다:
     * 1. 전역 안전망 - `queue.entry.max-quantity`(env `QUEUE_ENTRY_MAX_QUANTITY`, 기본 5).
     *    프론트가 1~5로 제한하고 있지만 API 직접 호출로 우회 가능하므로 서버에서 강제한다
     *    (수량 무제한 진입은 엄격한 FIFO에서 대기열 전체를 인질로 잡는 벡터가 된다).
     * 2. 드롭별 정책 - product 소유 `drop:{dropId}` 해시의 `limitPerUser`(설정된 드롭만).
     */
    QUANTITY_LIMIT_EXCEEDED(HttpStatus.BAD_REQUEST, "QUEUE_QUANTITY_LIMIT_EXCEEDED", "요청 수량이 허용 상한을 초과했습니다."),
    ;

    override fun getCode(): String = code

    override fun getMessage(): String = message

    override fun getHttpStatus(): HttpStatus = httpStatus
}
