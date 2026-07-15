package com.openat.queue.application.usecase

import com.openat.queue.application.dto.QueueStatusInfo

/** 입력 포트. 대기열 진입(없으면 등록, 있으면 현재 상태 반환)을 수행한다. */
fun interface EnterQueueUseCase {
    fun enter(dropId: String, userId: String, quantity: Int): QueueStatusInfo
}
