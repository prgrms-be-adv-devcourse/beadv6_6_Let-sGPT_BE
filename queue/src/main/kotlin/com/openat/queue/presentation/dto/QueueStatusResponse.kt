package com.openat.queue.presentation.dto

import com.openat.queue.application.dto.QueueStatusInfo
import com.openat.queue.domain.model.QueueStatus

/**
 * 컨트롤러 계층 출력 DTO(~Response 컨벤션).
 *
 * @param grantableNow `status == DECISION_REQUIRED`일 때만 값이 있음 - 지금 당장 받을 수 있는
 *   최대 수량(참고값, 실제 발급량은 `/decision` 호출 시점에 다시 계산됨).
 * @param optimisticMax `status == DECISION_REQUIRED`일 때만 값이 있음 - 계속 기다리면 받을 수
 *   있는 이론적 상한(총재고 - 확정). `optimisticMax < quantity`면 "포기/부분구매" 질문,
 *   그 이상이면 "기다림/부분구매" 질문이라는 뜻 - 클라이언트가 이 값으로 다이얼로그를 고른다.
 * @param soldOutReason `status == SOLD_OUT`일 때만 값이 있음 - `"CLOSED"`(마감 시각 경과)인지
 *   `"STOCK_EXHAUSTED"`(확정 재고 소진)인지 구분해 클라이언트가 정확한 문구를 보여주게 한다.
 */
data class QueueStatusResponse(
    val status: QueueStatus,
    val rank: Long?,
    val totalWaiting: Long?,
    val quantity: Int?,
    val grantableNow: Long?,
    val optimisticMax: Long?,
    val pollIntervalMs: Long,
    val soldOutReason: String?,
) {
    companion object {
        fun from(info: QueueStatusInfo): QueueStatusResponse =
            QueueStatusResponse(
                status = info.status,
                rank = info.rank,
                totalWaiting = info.totalWaiting,
                quantity = info.quantity,
                grantableNow = info.grantableNow,
                optimisticMax = info.optimisticMax,
                pollIntervalMs = info.pollIntervalMs,
                soldOutReason = info.soldOutReason,
            )
    }
}
