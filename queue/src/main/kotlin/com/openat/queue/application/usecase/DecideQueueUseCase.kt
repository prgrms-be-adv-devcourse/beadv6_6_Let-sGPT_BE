package com.openat.queue.application.usecase

import com.openat.queue.application.dto.QueueStatusInfo
import com.openat.queue.domain.model.DecisionChoice

/** 입력 포트. `DECISION_REQUIRED` 상태에 대한 사용자의 응답(WAIT/PARTIAL/GIVE_UP)을 처리한다. */
fun interface DecideQueueUseCase {
    fun decide(dropId: String, userId: String, choice: DecisionChoice): QueueStatusInfo
}
