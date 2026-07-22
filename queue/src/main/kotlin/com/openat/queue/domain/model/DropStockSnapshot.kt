package com.openat.queue.domain.model

import java.time.Instant

/**
 * 드롭 재고 스냅샷([com.openat.queue.domain.repository.StockRepository]가 반환). product가
 * 재고 변경의 유일한 권위(deduct.lua/rollback.lua)이고, 이 스냅샷은 product Redis를 직접 읽지
 * 않는다 - `remaining`은 큐가 이미 갖고 있는 `total - reserved`로 계산한 값이다(수학적으로
 * product의 실제 remaining과 항상 같음이 증명됨 - queue-remaining-sync 재설계 작업 참고).
 * 입장 가능 여부·품절 판정에만 참조한다.
 *
 * @param remaining 현재 가용 재고(선차감·복구가 모두 반영된 값, `total - reserved`로 계산).
 * @param closeAt 드롭 판매 종료 시각. null이면 마감 시각 미설정(무제한).
 * @param limitPerUser 드롭별 1인 구매 한도. null이면 미설정(무제한) - product의
 *   `DropCreateRequest.limitPerUser`가 선택 값이라 없는 드롭이 정상적으로 존재한다.
 */
data class DropStockSnapshot(
    val remaining: Long,
    val closeAt: Instant?,
    val limitPerUser: Int? = null,
)
