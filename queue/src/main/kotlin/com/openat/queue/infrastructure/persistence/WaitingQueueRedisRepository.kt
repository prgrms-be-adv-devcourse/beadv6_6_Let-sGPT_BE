package com.openat.queue.infrastructure.persistence

import com.openat.queue.domain.model.AdmittedEntry
import com.openat.queue.domain.model.WaitingTicket
import com.openat.queue.domain.repository.WaitingQueueRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Repository

/**
 * infrastructure 계층: 도메인 포트([WaitingQueueRepository])를 구현한다. product 모듈의
 * `DropCacheRedisAdaptor` 관례(`StringRedisTemplate` + `RedisScript.of(ClassPathResource(...))`)를
 * 그대로 따른다.
 *
 * admit.lua는 product가 쓰는 `drop:{dropId}` 해시(`remaining`/`closeAt`)를 직접 읽는다 -
 * 같은 Redis 인스턴스 안에서의 읽기 전용 결합이며, 그 계약(키/필드 이름)이 바뀌면 이쪽도
 * 함께 깨진다는 점을 `docs/_local/queue-stock-owner-requests.md`에 명시해 두었다.
 */
@Repository
class WaitingQueueRedisRepository(
    private val redisTemplate: StringRedisTemplate,
) : WaitingQueueRepository {

    @Suppress("UNCHECKED_CAST")
    private val enqueueOrAdmitScript: RedisScript<List<String>> =
        RedisScript.of(ClassPathResource("redis/enqueue-or-admit.lua"), List::class.java) as RedisScript<List<String>>
    private val sweepScript: RedisScript<Long> =
        RedisScript.of(ClassPathResource("redis/sweep.lua"), Long::class.java)
    private val sweepAdmittedScript: RedisScript<Long> =
        RedisScript.of(ClassPathResource("redis/sweep-admitted.lua"), Long::class.java)

    @Suppress("UNCHECKED_CAST")
    private val admitScript: RedisScript<List<String>> =
        RedisScript.of(ClassPathResource("redis/admit.lua"), List::class.java) as RedisScript<List<String>>
    private val decidePartialScript: RedisScript<Long> =
        RedisScript.of(ClassPathResource("redis/decide-partial.lua"), Long::class.java)

    override fun enqueueOrFastAdmit(
        dropId: String,
        userId: String,
        quantity: Int,
        ttlSeconds: Long,
        now: Instant,
    ): AdmittedEntry? {
        val result = redisTemplate.execute(
            enqueueOrAdmitScript,
            listOf(
                RedisKeys.queue(dropId),
                RedisKeys.heartbeat(dropId),
                RedisKeys.waitingQuantity(dropId),
                RedisKeys.productDrop(dropId),
                RedisKeys.outstanding(dropId),
                RedisKeys.admitted(dropId),
                RedisKeys.admittedQuantity(dropId),
                RedisKeys.activeDrops(),
            ),
            dropId,
            userId,
            quantity.toString(),
            now.toEpochMilli().toString(),
            ttlSeconds.toString(),
        ) ?: return null

        val admitted = result.getOrNull(0)?.toIntOrNull() ?: 0
        val grantedQuantity = result.getOrNull(1)?.toIntOrNull() ?: 0
        return if (admitted == 1) AdmittedEntry(userId = userId, quantity = grantedQuantity) else null
    }

    override fun ticketOf(dropId: String, userId: String): WaitingTicket? {
        val rank = redisTemplate.opsForZSet().rank(RedisKeys.queue(dropId), userId) ?: return null
        val total = redisTemplate.opsForZSet().zCard(RedisKeys.queue(dropId)) ?: 0
        val quantity = redisTemplate.opsForHash<String, String>()
            .get(RedisKeys.waitingQuantity(dropId), userId)?.toIntOrNull() ?: 1
        return WaitingTicket(rank = rank, totalWaiting = total, quantity = quantity)
    }

    override fun touchHeartbeat(dropId: String, userId: String, now: Instant) {
        redisTemplate.opsForZSet().add(RedisKeys.heartbeat(dropId), userId, now.toEpochMilli().toDouble())
    }

    override fun sizeOf(dropId: String): Long =
        redisTemplate.opsForZSet().zCard(RedisKeys.queue(dropId)) ?: 0

    override fun admittedQuantityOf(dropId: String, userId: String): Int? =
        redisTemplate.opsForValue().get(RedisKeys.admission(dropId, userId))?.toIntOrNull()

    override fun sweepExpired(dropId: String, now: Instant, heartbeatTtlMs: Long): Long {
        val cutoff = now.minus(heartbeatTtlMs, ChronoUnit.MILLIS)
        val removed = redisTemplate.execute(
            sweepScript,
            listOf(
                RedisKeys.queue(dropId),
                RedisKeys.heartbeat(dropId),
                RedisKeys.waitingQuantity(dropId),
                RedisKeys.decision(dropId),
            ),
            cutoff.toEpochMilli().toString(),
        )
        return removed ?: 0
    }

    override fun admitBatch(dropId: String, maxScan: Int, ttlSeconds: Long): List<AdmittedEntry> {
        val flat = redisTemplate.execute(
            admitScript,
            listOf(
                RedisKeys.queue(dropId),
                RedisKeys.heartbeat(dropId),
                RedisKeys.waitingQuantity(dropId),
                RedisKeys.productDrop(dropId),
                RedisKeys.outstanding(dropId),
                RedisKeys.admitted(dropId),
                RedisKeys.admittedQuantity(dropId),
                RedisKeys.decision(dropId),
            ),
            dropId,
            ttlSeconds.toString(),
            Instant.now().toEpochMilli().toString(),
            maxScan.toString(),
        ) ?: emptyList()

        val entries = ArrayList<AdmittedEntry>(flat.size / 2)
        var i = 0
        while (i < flat.size) {
            entries.add(AdmittedEntry(userId = flat[i], quantity = flat[i + 1].toInt()))
            i += 2
        }
        return entries
    }

    override fun sweepAdmittedTickets(dropId: String, now: Instant): Long {
        val reclaimed = redisTemplate.execute(
            sweepAdmittedScript,
            listOf(RedisKeys.admitted(dropId), RedisKeys.admittedQuantity(dropId), RedisKeys.outstanding(dropId)),
            now.toEpochMilli().toString(),
        )
        return reclaimed ?: 0
    }

    override fun outstandingOf(dropId: String): Long =
        redisTemplate.opsForValue().get(RedisKeys.outstanding(dropId))?.toLongOrNull() ?: 0

    override fun hasConfirmedWait(dropId: String, userId: String): Boolean =
        redisTemplate.opsForHash<String, String>().get(RedisKeys.decision(dropId), userId) == WAIT_CONFIRMED_VALUE

    override fun markWaitConfirmed(dropId: String, userId: String) {
        redisTemplate.opsForHash<String, String>().put(RedisKeys.decision(dropId), userId, WAIT_CONFIRMED_VALUE)
    }

    override fun admitSingle(dropId: String, userId: String, maxScan: Int, ttlSeconds: Long): AdmittedEntry? {
        val grant = redisTemplate.execute(
            decidePartialScript,
            listOf(
                RedisKeys.queue(dropId),
                RedisKeys.heartbeat(dropId),
                RedisKeys.waitingQuantity(dropId),
                RedisKeys.productDrop(dropId),
                RedisKeys.outstanding(dropId),
                RedisKeys.admitted(dropId),
                RedisKeys.admittedQuantity(dropId),
                RedisKeys.decision(dropId),
            ),
            dropId,
            userId,
            ttlSeconds.toString(),
            Instant.now().toEpochMilli().toString(),
            maxScan.toString(),
        ) ?: 0

        return if (grant > 0) AdmittedEntry(userId = userId, quantity = grant.toInt()) else null
    }

    override fun removeFromQueue(dropId: String, userId: String) {
        redisTemplate.opsForZSet().remove(RedisKeys.queue(dropId), userId)
        redisTemplate.opsForZSet().remove(RedisKeys.heartbeat(dropId), userId)
        redisTemplate.opsForHash<String, String>().delete(RedisKeys.waitingQuantity(dropId), userId)
        redisTemplate.opsForHash<String, String>().delete(RedisKeys.decision(dropId), userId)
    }

    override fun activeDropIds(): Set<String> =
        redisTemplate.opsForSet().members(RedisKeys.activeDrops()) ?: emptySet()

    override fun pruneIfIdle(dropId: String): Boolean {
        if (sizeOf(dropId) > 0 || outstandingOf(dropId) > 0) {
            return false
        }
        // 이 체크와 SREM 사이의 좁은 레이스(그 순간 다른 요청이 막 SADD)는 최악의 경우
        // 다음 스케줄러/스위퍼 tick에서 다시 SADD되어 자가치유되므로(active-drops는 권위
        // 있는 상태가 아니라 발견용 힌트일 뿐) 원자성이 필수는 아니다.
        return redisTemplate.opsForSet().remove(RedisKeys.activeDrops(), dropId) == 1L
    }

    companion object {
        private const val WAIT_CONFIRMED_VALUE = "WAIT_CONFIRMED"
    }
}
