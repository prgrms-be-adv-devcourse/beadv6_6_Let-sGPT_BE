package com.openat.queue.presentation.dto

import com.openat.queue.domain.model.DecisionChoice
import jakarta.validation.constraints.NotNull

/** `DECISION_REQUIRED` 상태에 대한 응답 바디. */
data class QueueDecisionRequest(
    @field:NotNull(message = "choice는 필수입니다.")
    val choice: DecisionChoice?,
)
