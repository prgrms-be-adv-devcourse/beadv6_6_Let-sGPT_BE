package com.openat.queue.infrastructure.persistence

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.openat.queue.domain.model.StockAdjustmentReason
import com.openat.queue.domain.repository.ConfirmedSalesRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository
import org.springframework.web.client.RestClient

/**
 * `confirmed`(확정 수량)는 큐 자신의 Redis 카운터(Kafka 컨슈머가 가감, [confirmed]).
 * `total`(총재고)은 product의 `GET /drops/{dropId}`를 최초 1회 호출해 캐싱한다(불변값이라
 * 재조회 불필요 - 캐시 miss일 때만 호출).
 */
@Repository
class ConfirmedSalesRedisRepository(
    private val redisTemplate: StringRedisTemplate,
    private val productRestClient: RestClient,
) : ConfirmedSalesRepository {

    private val log = LoggerFactory.getLogger(ConfirmedSalesRedisRepository::class.java)

    override fun confirmedOf(dropId: String): Long =
        redisTemplate.opsForValue().get(RedisKeys.confirmed(dropId))?.toLongOrNull() ?: 0

    override fun applyStockAdjustment(dropId: String, eventId: String, count: Int, reason: StockAdjustmentReason) {
        // SADD의 반환값(신규 추가된 개수)으로 "이 eventId를 처음 보는지"를 원자적으로 판정한다.
        // 이미 반영된 eventId면 0이 반환되어 가감을 건너뛴다(재전달에 대한 멱등 처리). COMPLETED와
        // REFUNDED는 같은 주문이라도 서로 다른 eventId를 갖는다는 전제(order/payment 팀과 합의됨) -
        // 그래서 같은 SET/카운터를 그대로 재사용해도 두 사건이 서로를 가리지 않는다.
        val added = redisTemplate.opsForSet().add(RedisKeys.confirmedSeen(dropId), eventId) ?: 0
        if (added <= 0) {
            log.debug("[confirmed-sales] dropId={} eventId={} 이미 반영된 이벤트 - 재전달 무시", dropId, eventId)
            return
        }
        // COMPLETED/REFUNDED는 교환 가능(덧셈/뺄셈)한 연산이라 두 이벤트의 도착 순서와 무관하게
        // 최종 합계가 항상 같다 - 이벤트 순서를 보장할 필요가 없다.
        val delta = when (reason) {
            StockAdjustmentReason.COMPLETED -> count.toLong()
            StockAdjustmentReason.REFUNDED -> -count.toLong()
        }
        redisTemplate.opsForValue().increment(RedisKeys.confirmed(dropId), delta)
    }

    override fun totalOf(dropId: String): Long? {
        redisTemplate.opsForValue().get(RedisKeys.total(dropId))?.toLongOrNull()?.let { return it }

        return try {
            val response = productRestClient.get()
                .uri("/drops/{dropId}", dropId)
                .retrieve()
                .body(DropTotalQuantityResponse::class.java)

            val total = response?.totalQuantity?.toLong()
            if (total != null) {
                redisTemplate.opsForValue().set(RedisKeys.total(dropId), total.toString())
            } else {
                log.warn("[confirmed-sales] dropId={} 응답에 totalQuantity가 없습니다.", dropId)
            }
            total
        } catch (e: Exception) {
            // product가 잠시 응답 불가여도 큐 자체는 죽으면 안 된다 - null 반환 시 호출부(QueueService)가
            // "낙관적 최대를 모른다"로 안전하게 처리한다(성급한 SHORTFALL 판정을 내리지 않음).
            log.warn("[confirmed-sales] dropId={} product 총재고 조회 실패: {}", dropId, e.message)
            null
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DropTotalQuantityResponse(val totalQuantity: Int? = null)
}
