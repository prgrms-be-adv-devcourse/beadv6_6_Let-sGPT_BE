package com.openat.queue.infrastructure.persistence

import com.openat.queue.domain.model.AdmittedEntry
import com.openat.queue.domain.model.DecisionState
import com.openat.queue.domain.model.QueueStatusSnapshot
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
 * 예전엔 admit.lua 등이 product 소유 `drop:{dropId}` 해시를 직접 읽었으나(MSA 경계 위반),
 * 지금은 `remaining`을 이 큐 소유의 `total(dropId) - reserved(dropId)`로 계산하고,
 * `closeAt`/`limitPerUser`는 `drop-meta(dropId)`(product REST 부트스트랩 캐시)에서 읽는다
 * (queue-remaining-sync 재설계 작업, `docs/_local/queue-remaining-reconciliation-contingency.md`
 * 참고).
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

    @Suppress("UNCHECKED_CAST")
    private val statusSnapshotScript: RedisScript<List<String>> =
        RedisScript.of(ClassPathResource("redis/status-snapshot.lua"), List::class.java) as RedisScript<List<String>>
    private val markAskedScript: RedisScript<Long> =
        RedisScript.of(ClassPathResource("redis/mark-asked.lua"), Long::class.java)
    private val sweepDecisionScript: RedisScript<String> =
        RedisScript.of(ClassPathResource("redis/sweep-decision.lua"), String::class.java)

    override fun enqueueOrFastAdmit(
        dropId: String,
        userId: String,
        quantity: Int,
        ttlSeconds: Long,
    ): AdmittedEntry? {
        val result = redisTemplate.execute(
            enqueueOrAdmitScript,
            listOf(
                RedisKeys.queue(dropId),
                RedisKeys.heartbeat(dropId),
                RedisKeys.waitingQuantity(dropId),
                RedisKeys.total(dropId),
                RedisKeys.reserved(dropId),
                RedisKeys.dropMeta(dropId),
                RedisKeys.outstanding(dropId),
                RedisKeys.admitted(dropId),
                RedisKeys.admittedQuantity(dropId),
                RedisKeys.activeDrops(),
            ),
            dropId,
            userId,
            quantity.toString(),
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

    override fun statusSnapshotOf(
        dropId: String,
        userId: String,
        now: Instant,
        touchHeartbeat: Boolean,
    ): QueueStatusSnapshot {
        val flat = redisTemplate.execute(
            statusSnapshotScript,
            listOf(
                RedisKeys.admission(dropId, userId),
                RedisKeys.queue(dropId),
                RedisKeys.heartbeat(dropId),
                RedisKeys.waitingQuantity(dropId),
                RedisKeys.dropMeta(dropId),
                RedisKeys.outstanding(dropId),
                RedisKeys.confirmed(dropId),
                RedisKeys.total(dropId),
                RedisKeys.decision(dropId),
                RedisKeys.reserved(dropId),
            ),
            userId,
            now.toEpochMilli().toString(),
            if (touchHeartbeat) "1" else "0",
        ) ?: error("status-snapshot.lua가 null을 반환했습니다(dropId=$dropId)")

        fun opt(index: Int): String? = flat.getOrNull(index)?.takeIf { it != ABSENT }

        // product의 closeAt 미설정 센티널("-1")은 음수 → null(마감 없음)로 정규화한다.
        val closeAtMillis = opt(5)?.toLongOrNull()
        val decisionRaw = opt(9)
        return QueueStatusSnapshot(
            admittedQuantity = opt(0)?.toIntOrNull(),
            rank = opt(1)?.toLongOrNull(),
            totalWaiting = opt(2)?.toLongOrNull() ?: 0,
            quantity = opt(3)?.toIntOrNull(),
            remaining = opt(4)?.toLongOrNull(),
            closeAt = if (closeAtMillis == null || closeAtMillis < 0) null else Instant.ofEpochMilli(closeAtMillis),
            outstanding = opt(6)?.toLongOrNull() ?: 0,
            confirmed = opt(7)?.toLongOrNull() ?: 0,
            total = opt(8)?.toLongOrNull(),
            decision = when {
                decisionRaw == null -> null
                decisionRaw.startsWith(WAIT_CONFIRMED_MARKER) -> {
                    // 포맷: "WAIT_CONFIRMED:<grantableNow>:<max|NA>". 배포 직후 재기동 전
                    // 레거시 값(콜론 접미사가 없거나 한 칸뿐인 경우)은 값을 몰랐던 것으로
                    // 보수적 취급한다(grantableNowAtConfirm=0, maxAtConfirm=null) - 어차피
                    // 이 값들은 활성 대기열의 일시적 상태라 서비스 재기동 시 큐 자체가
                    // 다시 채워지므로 장기 마이그레이션을 걱정할 데이터가 아니다.
                    val parts = decisionRaw.removePrefix(WAIT_CONFIRMED_MARKER).removePrefix(":").split(":")
                    val grantableNowAtConfirm = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                    val maxAtConfirm = parts.getOrNull(1)?.takeIf { it != NO_MAX_MARKER }?.toLongOrNull()
                    DecisionState.WaitConfirmed(grantableNowAtConfirm, maxAtConfirm)
                }
                decisionRaw.startsWith(ASKED_PREFIX) ->
                    decisionRaw.removePrefix(ASKED_PREFIX).toLongOrNull()?.let { DecisionState.Asked(it) }
                else -> null
            },
            reserved = opt(10)?.toLongOrNull() ?: 0,
        )
    }

    override fun markAskedIfAbsent(dropId: String, userId: String, now: Instant): Long =
        redisTemplate.execute(
            markAskedScript,
            listOf(RedisKeys.decision(dropId)),
            userId,
            now.toEpochMilli().toString(),
        ) ?: -1

    override fun sweepDecisionTimeout(dropId: String, now: Instant, timeoutMs: Long): String? =
        redisTemplate.execute(
            sweepDecisionScript,
            listOf(
                RedisKeys.queue(dropId),
                RedisKeys.heartbeat(dropId),
                RedisKeys.waitingQuantity(dropId),
                RedisKeys.decision(dropId),
                RedisKeys.total(dropId),
                RedisKeys.reserved(dropId),
                RedisKeys.outstanding(dropId),
            ),
            now.toEpochMilli().toString(),
            timeoutMs.toString(),
        )?.takeIf { it.isNotEmpty() }

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
                RedisKeys.total(dropId),
                RedisKeys.reserved(dropId),
                RedisKeys.dropMeta(dropId),
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

    override fun markWaitConfirmed(dropId: String, userId: String, grantableNowAtConfirm: Long, maxAtConfirm: Long?) {
        val value = "$WAIT_CONFIRMED_MARKER:$grantableNowAtConfirm:${maxAtConfirm?.toString() ?: NO_MAX_MARKER}"
        redisTemplate.opsForHash<String, String>().put(RedisKeys.decision(dropId), userId, value)
    }

    override fun admitSingle(dropId: String, userId: String, ttlSeconds: Long): AdmittedEntry? {
        val grant = redisTemplate.execute(
            decidePartialScript,
            listOf(
                RedisKeys.queue(dropId),
                RedisKeys.heartbeat(dropId),
                RedisKeys.waitingQuantity(dropId),
                RedisKeys.total(dropId),
                RedisKeys.reserved(dropId),
                RedisKeys.outstanding(dropId),
                RedisKeys.admitted(dropId),
                RedisKeys.admittedQuantity(dropId),
                RedisKeys.decision(dropId),
            ),
            dropId,
            userId,
            ttlSeconds.toString(),
            Instant.now().toEpochMilli().toString(),
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
        private const val WAIT_CONFIRMED_MARKER = "WAIT_CONFIRMED"
        private const val NO_MAX_MARKER = "NA"
        private const val ASKED_PREFIX = "ASKED:"

        /** status-snapshot.lua의 "부재" 센티널 - Lua 멀티불크는 nil을 표현할 수 없어 '-'로 채운다. */
        private const val ABSENT = "-"
    }
}
