package com.openat.queue.infrastructure.persistence

import com.openat.queue.domain.model.StockAdjustmentReason
import com.openat.queue.domain.repository.ConfirmedSalesRepository
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Repository

/**
 * `confirmed`(확정 수량)는 큐 자신의 Redis 카운터(Kafka 컨슈머가 가감, [confirmed]).
 * `total`(총재고) 부트스트랩은 [DropSnapshotBootstrapper]에 위임한다(`drop-meta` 캐시도 같은
 * REST 호출에서 함께 채워야 해서 - queue-remaining-sync 재설계 작업 참고).
 */
@Repository
class ConfirmedSalesRedisRepository(
    private val redisTemplate: StringRedisTemplate,
    private val dropSnapshotBootstrapper: DropSnapshotBootstrapper,
) : ConfirmedSalesRepository {

    private val log = LoggerFactory.getLogger(ConfirmedSalesRedisRepository::class.java)

    private val applyStockAdjustmentScript: RedisScript<Long> =
        RedisScript.of(ClassPathResource("redis/apply-stock-adjustment.lua"), Long::class.java)

    override fun confirmedOf(dropId: String): Long =
        redisTemplate.opsForValue().get(RedisKeys.confirmed(dropId))?.toLongOrNull() ?: 0

    override fun applyStockAdjustment(dropId: String, eventId: String, count: Int, reason: StockAdjustmentReason) {
        // 멱등 마킹(SADD)과 카운터 가감(INCRBY)을 Lua 하나로 원자 실행한다 - 두 명령 사이에서
        // 앱이 크래시하면 "마킹은 됐는데 반영은 안 된" 영구 drift가 생기던 문제를 닫는다
        // (apply-stock-adjustment.lua 헤더 주석 참고). COMPLETED와 REFUNDED는 같은 주문이라도
        // 서로 다른 eventId를 갖는다는 전제(order/payment 팀과 합의됨)라 같은 SET/카운터를
        // 재사용해도 두 사건이 서로를 가리지 않는다.
        // COMPLETED/REFUNDED는 교환 가능(덧셈/뺄셈)한 연산이라 도착 순서와 무관하게 최종 합계가
        // 항상 같다 - 이벤트 순서를 보장할 필요가 없다.
        val delta = when (reason) {
            StockAdjustmentReason.COMPLETED -> count.toLong()
            StockAdjustmentReason.REFUNDED -> -count.toLong()
            StockAdjustmentReason.CREATED, StockAdjustmentReason.CANCELLED ->
                error("ConfirmedSalesRepository는 COMPLETED/REFUNDED만 처리한다(reason=$reason) - 라우팅 버그")
        }
        val applied = redisTemplate.execute(
            applyStockAdjustmentScript,
            listOf(RedisKeys.confirmedSeen(dropId), RedisKeys.confirmed(dropId)),
            eventId,
            delta.toString(),
        ) ?: 0
        if (applied <= 0) {
            log.debug("[confirmed-sales] dropId={} eventId={} 이미 반영된 이벤트 - 재전달 무시", dropId, eventId)
        }
    }

    override fun totalOf(dropId: String): Long? = dropSnapshotBootstrapper.ensureTotalCached(dropId)
}
