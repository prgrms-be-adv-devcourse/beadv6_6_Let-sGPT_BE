package com.openat.queue.domain.repository

import com.openat.queue.domain.model.DropStockSnapshot

/**
 * product 모듈이 소유한 드롭 재고 캐시를 읽기 전용으로 참조하는 출력 포트.
 *
 * 큐는 이 값을 절대 쓰지 않는다(product가 유일한 쓰기 권위, 결정13 유지) - 입장 가능 여부는
 * [com.openat.queue.domain.repository.WaitingQueueRepository.admitBatch]가 내부적으로
 * 원자적 Lua에서 같은 값을 직접 읽어 판정하고, 이 포트는 그 외(SOLD_OUT 판정 등) 조회용이다.
 */
interface StockRepository {

    /** 캐시가 없으면(워밍 전이거나 존재하지 않는 드롭) null. */
    fun snapshotOf(dropId: String): DropStockSnapshot?
}
