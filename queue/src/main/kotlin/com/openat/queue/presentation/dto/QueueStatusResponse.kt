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
 * @param availableChoices `status == DECISION_REQUIRED`일 때만 값이 있음 - 서버가 내려주는
 *   선택지 목록(`WAIT`/`PARTIAL`/`GIVE_UP`). 클라이언트는 이 목록에 있는 버튼만 그린다
 *   (예: 지금 줄 수 있는 수량이 0이면 PARTIAL이 빠진다).
 * @param decisionDeadlineEpochMs `status == DECISION_REQUIRED`이고 무응답 타임아웃 대상일 때만
 *   값이 있음 - 이 시각(epoch ms)까지 응답이 없으면 대기열에서 제거되므로 클라이언트는 이
 *   값으로 카운트다운("N초 내 선택하지 않으면 대기열에서 제외됩니다")을 보여줘야 한다.
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
    val availableChoices: List<String>?,
    val decisionDeadlineEpochMs: Long?,
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
                availableChoices = info.availableChoices,
                decisionDeadlineEpochMs = info.decisionDeadlineEpochMs,
            )
    }
}
