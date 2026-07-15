package com.openat.queue.infrastructure.kafka.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.openat.queue.domain.repository.ConfirmedSalesRepository
import com.openat.queue.infrastructure.kafka.event.StockAdjustmentEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * order의 `StockAdjustment` 이벤트(결제 확정/환불)를 구독해 확정(결제완료) 수량을 큐 자신의
 * Redis에 반영한다([ConfirmedSalesRepository.applyStockAdjustment]) - "미확정 재고
 * (총재고 - remaining - 확정)" 계산의 한 축. 정적 hot-drops 목록이 없으므로 모든 dropId의
 * 조정을 무조건 반영한다.
 */
@Component
class StockAdjustmentConsumer(
    private val objectMapper: ObjectMapper,
    private val confirmedSalesRepository: ConfirmedSalesRepository,
) {

    private val log = LoggerFactory.getLogger(StockAdjustmentConsumer::class.java)

    @KafkaListener(
        topics = ["\${queue.kafka.topic.stock-adjustment}"],
        groupId = "\${spring.kafka.consumer.group-id}",
    )
    fun onStockAdjustment(record: ConsumerRecord<String, String>) {
        val payload = record.value()
        val event = try {
            objectMapper.readValue(payload, StockAdjustmentEvent::class.java)
        } catch (e: Exception) {
            log.error("[stock-adjustment] payload 파싱 실패: {}", payload, e)
            return
        }

        val eventId = event.eventId
        val dropId = event.dropId
        val count = event.count
        val reason = event.reason
        if (eventId == null || dropId == null || count == null || reason == null) {
            log.warn("[stock-adjustment] 필수 필드 누락 - 계약 위반 가능성, 건너뜀. event={}", event)
            return
        }

        confirmedSalesRepository.applyStockAdjustment(dropId.toString(), eventId.toString(), count, reason)
        log.info(
            "[stock-adjustment] dropId={} eventId={} count={} reason={} 확정 수량 반영",
            dropId, eventId, count, reason,
        )
    }
}
