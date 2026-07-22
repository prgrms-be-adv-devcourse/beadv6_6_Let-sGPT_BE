package com.openat.queue.infrastructure.persistence

import com.openat.queue.domain.model.StockAdjustmentReason
import com.openat.queue.domain.repository.ReservedStockRepository
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Repository

/**
 * `ConfirmedSalesRedisRepository`와 자매 구현체 - 같은 `apply-stock-adjustment.lua`를
 * 재사용하되(SADD+INCRBY 원자 실행, 이벤트별로 별개의 Redis 키 쌍을 다루는 범용 스크립트라
 * 새 Lua가 필요 없다) `reserved:{dropId}`/`reserved:{dropId}:seen` 키에 적용한다.
 */
@Repository
class ReservedStockRedisRepository(
    private val redisTemplate: StringRedisTemplate,
) : ReservedStockRepository {

    private val log = LoggerFactory.getLogger(ReservedStockRedisRepository::class.java)

    private val applyStockAdjustmentScript: RedisScript<Long> =
        RedisScript.of(ClassPathResource("redis/apply-stock-adjustment.lua"), Long::class.java)
    private val applyCreatedReservationScript: RedisScript<Long> =
        RedisScript.of(ClassPathResource("redis/apply-created-reservation.lua"), Long::class.java)

    override fun reservedOf(dropId: String): Long =
        redisTemplate.opsForValue().get(RedisKeys.reserved(dropId))?.toLongOrNull() ?: 0

    override fun applyReservationAdjustment(dropId: String, eventId: String, count: Int, reason: StockAdjustmentReason) {
        // CANCELLED만 여기로 온다 - CREATED는 outstanding과 원자적으로 같이 처리해야 해서
        // applyCreatedReservationAndReleaseOutstanding()을 대신 쓴다(호출부인
        // StockAdjustmentConsumer 참고).
        val delta = when (reason) {
            StockAdjustmentReason.CANCELLED -> -count.toLong()
            StockAdjustmentReason.CREATED ->
                error("CREATED는 applyCreatedReservationAndReleaseOutstanding()으로 처리해야 한다 - 라우팅 버그")
            StockAdjustmentReason.COMPLETED, StockAdjustmentReason.REFUNDED ->
                error("ReservedStockRepository는 CREATED/CANCELLED만 처리한다(reason=$reason) - 라우팅 버그")
        }
        val applied = redisTemplate.execute(
            applyStockAdjustmentScript,
            listOf(RedisKeys.reservedSeen(dropId), RedisKeys.reserved(dropId)),
            eventId,
            delta.toString(),
        ) ?: 0
        if (applied <= 0) {
            log.debug("[reserved-stock] dropId={} eventId={} 이미 반영된 이벤트 - 재전달 무시", dropId, eventId)
        }
    }

    override fun applyCreatedReservationAndReleaseOutstanding(dropId: String, eventId: String, count: Int) {
        val applied = redisTemplate.execute(
            applyCreatedReservationScript,
            listOf(RedisKeys.reservedSeen(dropId), RedisKeys.reserved(dropId), RedisKeys.outstanding(dropId)),
            eventId,
            count.toString(),
        ) ?: 0
        if (applied <= 0) {
            log.debug("[reserved-stock] dropId={} eventId={} 이미 반영된 CREATED 이벤트 - 재전달 무시", dropId, eventId)
        }
    }
}
