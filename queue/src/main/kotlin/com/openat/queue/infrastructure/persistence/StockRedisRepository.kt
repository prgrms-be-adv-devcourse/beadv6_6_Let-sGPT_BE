package com.openat.queue.infrastructure.persistence

import com.openat.queue.domain.model.DropStockSnapshot
import com.openat.queue.domain.repository.StockRepository
import java.time.Instant
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository

/**
 * product 모듈이 쓰는 `drop:{dropId}` 해시를 읽기 전용으로 참조한다(`DropCacheRedisAdaptor`와
 * 동일 키/필드 계약 - `remaining`, `closeAt`). 큐는 이 값을 절대 쓰지 않는다.
 */
@Repository
class StockRedisRepository(
    private val redisTemplate: StringRedisTemplate,
) : StockRepository {

    override fun snapshotOf(dropId: String): DropStockSnapshot? {
        val values = redisTemplate.opsForHash<String, String>()
            .multiGet(RedisKeys.productDrop(dropId), listOf(RedisKeys.PRODUCT_REMAINING_FIELD, "closeAt"))

        val remaining = values.getOrNull(0)?.toLongOrNull() ?: return null
        val closeAtMillis = values.getOrNull(1)?.toLongOrNull()
        val closeAt = if (closeAtMillis == null || closeAtMillis < 0) null else Instant.ofEpochMilli(closeAtMillis)

        return DropStockSnapshot(remaining = remaining, closeAt = closeAt)
    }
}
