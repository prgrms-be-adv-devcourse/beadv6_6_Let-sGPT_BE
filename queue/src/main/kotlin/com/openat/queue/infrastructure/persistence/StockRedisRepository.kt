package com.openat.queue.infrastructure.persistence

import com.openat.queue.domain.model.DropStockSnapshot
import com.openat.queue.domain.repository.StockRepository
import java.time.Instant
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Repository

/**
 * 예전엔 product 소유 `drop:{dropId}` 해시를 직접 HGET했다(MSA 경계 위반 - 다른 모듈의
 * 내부 데이터스토어를 직접 침범). 지금은:
 * - `remaining`을 이 큐 소유의 `total(dropId) - reserved(dropId)`로 계산한다(`total-reserved`가
 *   product의 실제 remaining과 수학적으로 항상 같음이 증명됨 - queue-remaining-sync 재설계
 *   작업, `docs/_local/piped-snuggling-cascade.md` 참고).
 * - `closeAt`/`limitPerUser`는 queue 소유 `drop-meta:{dropId}` 캐시(부트스트랩 시 product REST
 *   1회 호출로 채움, [DropSnapshotBootstrapper])에서 읽는다.
 */
@Repository
class StockRedisRepository(
    private val redisTemplate: StringRedisTemplate,
    private val dropSnapshotBootstrapper: DropSnapshotBootstrapper,
) : StockRepository {

    override fun snapshotOf(dropId: String): DropStockSnapshot? {
        // total이 캐시에 없으면 여기서 부트스트랩(REST 1회)을 트리거한다 - drop-meta도 같은
        // 호출에서 함께 채워진다. total을 못 구하면(product 응답 불가 등) 예전 "drop 해시
        // 미워밍"과 동일하게 안전 저하(null 반환 - 성급히 판단하지 않고 대기시킴).
        val total = dropSnapshotBootstrapper.ensureTotalCached(dropId) ?: return null
        val reserved = redisTemplate.opsForValue().get(RedisKeys.reserved(dropId))?.toLongOrNull() ?: 0

        var remaining = total - reserved
        // 방어적 클램프(위생 코드 - 실제 드리프트를 고치는 게 아니라 이상값이 판정 로직에
        // 새어들어가는 것만 막는다).
        if (remaining < 0) remaining = 0
        if (remaining > total) remaining = total

        val meta = redisTemplate.opsForHash<String, String>()
            .multiGet(RedisKeys.dropMeta(dropId), listOf("closeAt", "limitPerUser"))
        val closeAtMillis = meta.getOrNull(0)?.toLongOrNull()
        val closeAt = if (closeAtMillis == null || closeAtMillis < 0) null else Instant.ofEpochMilli(closeAtMillis)
        // 부트스트랩의 UNSET_SENTINEL("-1") 또는 필드 부재 → 한도 미설정(null)
        val limitPerUser = meta.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }

        return DropStockSnapshot(remaining = remaining, closeAt = closeAt, limitPerUser = limitPerUser)
    }
}
