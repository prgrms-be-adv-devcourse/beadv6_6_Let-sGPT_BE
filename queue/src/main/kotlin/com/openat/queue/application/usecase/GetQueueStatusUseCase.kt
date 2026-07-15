package com.openat.queue.application.usecase

import com.openat.queue.application.dto.QueueStatusInfo

/** 입력 포트. 클라이언트가 주기적으로 폴링하는 순번/입장 가능 여부 조회. */
fun interface GetQueueStatusUseCase {
    fun status(dropId: String, userId: String): QueueStatusInfo
}
