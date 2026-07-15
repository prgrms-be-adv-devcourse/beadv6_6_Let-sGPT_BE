package com.openat.queue.application.dto

import com.openat.queue.domain.model.QueueStatus

/**
 * 서비스 계층 출력 DTO(~Info 컨벤션). 통신 어댑터가 이를 각자의 응답 포맷으로 변환한다.
 *
 * @param grantableNow `status == DECISION_REQUIRED`일 때만 값이 있음 - 지금 당장 받을 수 있는
 *   최대 수량(참고값, 실제 발급량은 결정(decide) 시점에 다시 계산됨).
 * @param optimisticMax `status == DECISION_REQUIRED`일 때만 값이 있음 - 계속 기다리면 받을 수
 *   있는 이론적 상한(총재고 - 확정). `optimisticMax < quantity`면 "포기/부분구매"(SHORTFALL),
 *   그 이상이면 "기다림/부분구매"(PARTIAL_OR_WAIT) 질문이라는 뜻 - 클라이언트가 이 값으로
 *   두 다이얼로그 중 무엇을 보여줄지 판단한다.
 * @param soldOutReason `status == SOLD_OUT`일 때만 값이 있음 - "CLOSED"(마감 시각 경과)인지
 *   "STOCK_EXHAUSTED"(확정 재고 소진)인지 구분해 클라이언트가 정확한 문구를 보여주게 한다.
 */
data class QueueStatusInfo(
    val status: QueueStatus,
    val rank: Long?,
    val totalWaiting: Long?,
    val quantity: Int?,
    val grantableNow: Long?,
    val optimisticMax: Long?,
    val pollIntervalMs: Long,
    val soldOutReason: String? = null,
)
