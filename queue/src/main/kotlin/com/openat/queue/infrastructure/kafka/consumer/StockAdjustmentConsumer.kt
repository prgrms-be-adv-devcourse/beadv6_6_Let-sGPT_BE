package com.openat.queue.infrastructure.kafka.consumer

import com.fasterxml.jackson.databind.ObjectMapper
import com.openat.queue.domain.model.StockAdjustmentReason
import com.openat.queue.domain.repository.ConfirmedSalesRepository
import com.openat.queue.domain.repository.ReservedStockRepository
import com.openat.queue.infrastructure.kafka.event.StockAdjustmentEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.BackOff
import org.springframework.kafka.annotation.DltHandler
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.annotation.RetryableTopic
import org.springframework.stereotype.Component

/**
 * order의 `StockAdjustment` 이벤트를 구독해 사유(reason)에 따라 서로 다른 큐 소유 카운터에
 * 반영한다 - 정적 hot-drops 목록이 없으므로 모든 dropId의 조정을 무조건 반영한다.
 *
 * - [StockAdjustmentReason.COMPLETED]/[StockAdjustmentReason.REFUNDED] → 확정(결제완료)
 *   수량([ConfirmedSalesRepository]) - "대기열 존속"(매진 여부) 판정에 쓰인다.
 * - [StockAdjustmentReason.CREATED]/[StockAdjustmentReason.CANCELLED] → 선점(미확정) 수량
 *   ([ReservedStockRepository]) - "구매자 입장 시 분기"(기다림/조정구매/포기) 판정에 쓰인다.
 *
 * 버그 이력(queue-remaining-sync 재설계 작업 중 발견, 이 재설계와는 별개의 기존 결함):
 * 예전엔 이 컨슈머에 에러 핸들러/DLQ가 전혀 없었다 - 처리 중 예외(예: Redis 순단)가 나면
 * 스프링 카프카 기본 동작(제한된 재시도 후 조용히 오프셋을 넘김)으로 그 메시지가 어디에도
 * 흔적을 안 남기고 스킵될 수 있었다(order 쪽 `KafkaStringConfig`에는 이미 DLQ가 있었는데
 * queue만 없었음). `@RetryableTopic`으로 재시도 후에도 실패하면 `-dlt` 토픽에 보존해
 * 나중에 확인/재처리할 수 있게 한다 - `total - reserved` 계산의 정확도에 직접 기여하는
 * 하드닝(reserved/confirmed 갱신이 조용히 유실되지 않도록).
 */
@Component
class StockAdjustmentConsumer(
    private val objectMapper: ObjectMapper,
    private val confirmedSalesRepository: ConfirmedSalesRepository,
    private val reservedStockRepository: ReservedStockRepository,
) {

    private val log = LoggerFactory.getLogger(StockAdjustmentConsumer::class.java)

    @RetryableTopic(
        attempts = "4",
        backOff = BackOff(delay = 1_000, multiplier = 2.0),
        dltTopicSuffix = "-dlt",
        autoCreateTopics = "true",
    )
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

        when (reason) {
            StockAdjustmentReason.COMPLETED, StockAdjustmentReason.REFUNDED -> {
                confirmedSalesRepository.applyStockAdjustment(dropId.toString(), eventId.toString(), count, reason)
                log.info(
                    "[stock-adjustment] dropId={} eventId={} count={} reason={} 확정 수량 반영",
                    dropId, eventId, count, reason,
                )
            }
            // CREATED는 reserved 증가와 outstanding 감소를 원자적으로 같이 처리한다(핸드오프 -
            // apigateway는 성공 응답에서 더 이상 outstanding을 즉시 안 푼다, "이중 공백" 레이스
            // 수정 참고: apply-created-reservation.lua / ReservedStockRepository 헤더 주석).
            StockAdjustmentReason.CREATED -> {
                reservedStockRepository.applyCreatedReservationAndReleaseOutstanding(
                    dropId.toString(), eventId.toString(), count,
                )
                log.info(
                    "[stock-adjustment] dropId={} eventId={} count={} reason={} 선점 수량 반영 + outstanding 인계",
                    dropId, eventId, count, reason,
                )
            }
            StockAdjustmentReason.CANCELLED -> {
                reservedStockRepository.applyReservationAdjustment(dropId.toString(), eventId.toString(), count, reason)
                log.info(
                    "[stock-adjustment] dropId={} eventId={} count={} reason={} 선점 수량 반영",
                    dropId, eventId, count, reason,
                )
            }
        }
    }

    // @RetryableTopic이 재시도(4회, 1s/2s/4s 백오프)를 전부 소진한 뒤에도 실패하면 여기로
    // 온다 - reserved/confirmed 반영이 유실됐다는 뜻이므로, 조용히 버리지 않고 크게 로그를
    // 남겨 운영에서 발견/재처리할 수 있게 한다(메시지 자체는 `{topic}-dlt` 토픽에 보존됨).
    @DltHandler
    fun onDlt(record: ConsumerRecord<String, String>) {
        log.error(
            "[stock-adjustment] 재시도 소진 - DLT로 이동. topic={} partition={} offset={} payload={}",
            record.topic(), record.partition(), record.offset(), record.value(),
        )
    }
}
