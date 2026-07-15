package com.openat.queue.infrastructure.kafka.event

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openat.queue.domain.model.StockAdjustmentReason
import java.util.UUID

/**
 * order 모듈이 발행하는 `StockAdjustment` 이벤트의 페이로드(order/payment 팀과 합의된 계약).
 * "결제 확정"과 "환불"이 각각 별도 사건으로 발행된다 - REFUNDED는 이전에 COMPLETED로 반영된
 * 확정 수량을 되돌리는 용도([StockAdjustmentReason]).
 *
 * `eventId`는 Kafka의 at-least-once 재전달에 대한 멱등 처리 키다(같은 주문이라도 COMPLETED와
 * REFUNDED는 서로 다른 사건이므로 서로 다른 eventId를 가져야 한다 - order/payment 팀과 합의됨).
 *
 * 필드가 하나라도 비어 있으면(파싱 실패/계약 위반) 안전하게 건너뛴다 - 역직렬화 자체가
 * 실패하면 안 되므로 전부 nullable로 선언한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class StockAdjustmentEvent(
    val eventId: UUID? = null,
    val dropId: UUID? = null,
    val count: Int? = null,
    val reason: StockAdjustmentReason? = null,
)
