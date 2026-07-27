package com.openat.queue.infrastructure.persistence

import com.openat.queue.domain.model.DropStockSnapshot
import com.openat.queue.domain.repository.StockRepository
import java.time.Instant
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono

/**
 * 예전엔 product 소유 `drop:{dropId}` 해시를 직접 HGET했다(MSA 경계 위반 - 다른 모듈의
 * 내부 데이터스토어를 직접 침범). 지금은:
 * - `remaining`을 이 큐 소유의 `total(dropId) - reserved(dropId)`로 계산한다(`total-reserved`가
 *   product의 실제 remaining과 수학적으로 항상 같음이 증명됨 - queue-remaining-sync 재설계
 *   작업, `docs/_local/piped-snuggling-cascade.md` 참고).
 * - `closeAt`/`limitPerUser`는 queue 소유 `drop-meta:{dropId}` 캐시(부트스트랩 시 product REST
 *   1회 호출로 채움, [DropSnapshotBootstrapper])에서 읽는다.
 *
 * feature/queue-remaining-sync(코루틴 전환): Redis 호출은 여전히 `ReactiveStringRedisTemplate`
 * 기반이고(논블로킹), 이 suspend fun 안에서 `awaitSingle()`로 값을 받는다. 예외 하나는 그대로다 -
 * [DropSnapshotBootstrapper.ensureTotalCachedReactive]는 내부적으로 여전히 블로킹(`RestClient`로
 * product REST를 호출)이라 `Schedulers.boundedElastic()`으로 감싼 `Mono<Long>`을 돌려준다(REST
 * 호출이지 Redis가 아니고, 드롭당 캐시 미스 1회뿐이라 이번 전환 범위 밖으로 명시적으로 남겨둠 -
 * DropSnapshotBootstrapper 클래스 주석 참고). 여기서는 그 Mono를 `awaitSingleOrNull()`로
 * 한 번 구독하기만 한다.
 */
@Repository
class StockRedisRepository(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val dropSnapshotBootstrapper: DropSnapshotBootstrapper,
) : StockRepository {

    override suspend fun snapshotOf(dropId: String): DropStockSnapshot? {
        val total = dropSnapshotBootstrapper.ensureTotalCachedReactive(dropId).awaitSingleOrNull() ?: return null

        val reservedMono = redisTemplate.opsForValue().get(RedisKeys.reserved(dropId))
            .mapNotNull { it.toLongOrNull() }.defaultIfEmpty(0)
        val metaMono = redisTemplate.opsForHash<String, String>()
            .multiGet(RedisKeys.dropMeta(dropId), listOf("closeAt", "limitPerUser"))
        val reservedAndMeta = Mono.zip(reservedMono, metaMono).awaitSingle()
        val reserved = reservedAndMeta.t1
        val meta = reservedAndMeta.t2

        var remaining = total - reserved
        // 방어적 클램프(위생 코드 - 실제 드리프트를 고치는 게 아니라 이상값이 판정 로직에
        // 새어들어가는 것만 막는다).
        if (remaining < 0) remaining = 0
        if (remaining > total) remaining = total

        val closeAtMillis = meta.getOrNull(0)?.toLongOrNull()
        val closeAt = if (closeAtMillis == null || closeAtMillis < 0) null else Instant.ofEpochMilli(closeAtMillis)
        // 부트스트랩의 UNSET_SENTINEL("-1") 또는 필드 부재 → 한도 미설정(null)
        val limitPerUser = meta.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 }

        return DropStockSnapshot(remaining = remaining, closeAt = closeAt, limitPerUser = limitPerUser)
    }
}
