package com.openat.queue.domain.model

import java.time.Instant

/**
 * product 모듈이 소유한 드롭 재고 캐시의 읽기 전용 스냅샷([com.openat.queue.domain.repository.StockRepository]가 반환).
 * 큐는 이 값을 절대 쓰지 않고(product가 유일한 쓰기 권위) 입장 가능 여부·품절 판정에만 참조한다.
 *
 * @param remaining 현재 가용 재고(선차감·복구가 모두 반영된 값).
 * @param closeAt 드롭 판매 종료 시각. null이면 마감 시각 미설정(무제한).
 */
data class DropStockSnapshot(
    val remaining: Long,
    val closeAt: Instant?,
)
