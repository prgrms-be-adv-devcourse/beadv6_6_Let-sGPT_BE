package com.openat.queue.presentation.dto

import jakarta.validation.constraints.Min

/**
 * 대기열 진입 요청 바디. 사용자 수가 아니라 "요청 수량"으로 재고를 통제하기 위해 필요하다
 * (1인당 여러 개 구매 가능 - 실제 상한은 product의 드롭별 `limitPerUser`가 주문 시점에 최종
 * 판정하므로, 여기서는 최소한의 정합성(1개 이상)만 검증한다).
 */
data class QueueEntryRequest(
    @field:Min(1, message = "quantity는 1 이상이어야 합니다.")
    val quantity: Int = 1,
)
