package com.openat.queue.application.usecase

import com.openat.queue.domain.model.AdmittedEntry

/** 입력 포트. 스케줄러가 주기적으로 호출해 재고가 허용하는 만큼 대기열 앞을 입장 처리한다. */
fun interface AdmitWaitersUseCase {
    /** @return 이번 tick에서 입장 처리된 (userId, quantity) 목록 */
    fun admitBatch(dropId: String): List<AdmittedEntry>
}
