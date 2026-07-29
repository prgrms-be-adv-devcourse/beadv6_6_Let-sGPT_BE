package com.openat.queue.infrastructure.persistence

import com.openat.queue.domain.model.AdmittedEntry
import com.openat.queue.domain.model.DecisionState
import com.openat.queue.domain.model.QueueStatusSnapshot
import com.openat.queue.domain.model.WaitingTicket
import com.openat.queue.domain.repository.WaitingQueueRepository
import com.openat.queue.infrastructure.trace.QueueTraceBridge
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Repository
import reactor.core.publisher.Mono

/**
 * infrastructure 계층: 도메인 포트([WaitingQueueRepository])를 구현한다.
 *
 * feature/queue-remaining-sync(코루틴 전환): `ReactiveStringRedisTemplate`(논블로킹 Redis
 * 드라이버) 자체는 그대로 쓴다 - 병목 프로파일링 결과 Redis 명령 실행 자체는 병목이 아니었고
 * (실측: VUS 300 정상부하에서 Redis CPU 10~13%, 순간 최대 34% - 자세한 근거는
 * three-stage-story.md 참고), 이번 전환은 "Redis를 더 빠르게" 만드는 작업이 아니라 "리액터
 * 연산자 체인 대신 코루틴 문법으로 같은 호출을 표현"하는 작업이다. 여러 Redis 호출을 동시에
 * 보내야 하는 자리(예: [ticketOf]의 3개 조회)는 `Mono.zip`으로 여전히 파이프라이닝하되,
 * 그 결과를 메서드 경계에서 `awaitSingle()`로 한 번만 구독한다 - 그 외 단일 호출은
 * `execute(...).awaitSingleOrNull()` 형태로 직접 값을 받는다.
 */
@Repository
class WaitingQueueRedisRepository(
    private val redisTemplate: ReactiveStringRedisTemplate,
    private val queueTraceBridge: QueueTraceBridge,
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
    private val removeFromQueueScript: RedisScript<Long> =
        RedisScript.of(ClassPathResource("redis/remove-from-queue.lua"), Long::class.java)

    override suspend fun enqueueOrFastAdmit(
        dropId: String,
        userId: String,
        quantity: Int,
        ttlSeconds: Long,
    ): AdmittedEntry? {
        // enqueue-or-admit Lua는 재고가 있으면 이 자리에서 즉시 입장시키고, 대기로 가더라도 admit
        // 스케줄러가 곧바로 다음 tick에 입장시킬 수 있다. 따라서 traceparent 저장은 반드시 Lua "이전"에
        // await로 완료해야 admit 측 조회가 저장을 앞지르지 않는다. 즉시 입장으로 링크되지 않는 고아
        // 항목은 해시 TTL이 청소하므로 무해하다.
        queueTraceBridge.captureEnqueue(dropId, userId)
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
            listOf(dropId, userId, quantity.toString(), ttlSeconds.toString()),
        ).awaitFirstOrNull() ?: return null
        val admitted = result.getOrNull(0)?.toIntOrNull() ?: 0
        val grantedQuantity = result.getOrNull(1)?.toIntOrNull() ?: 0
        return if (admitted == 1) AdmittedEntry(userId = userId, quantity = grantedQuantity) else null
    }

    override suspend fun ticketOf(dropId: String, userId: String): WaitingTicket? {
        val tuple = Mono.zip(
            redisTemplate.opsForZSet().rank(RedisKeys.queue(dropId), userId),
            redisTemplate.opsForZSet().size(RedisKeys.queue(dropId)).defaultIfEmpty(0),
            redisTemplate.opsForHash<String, String>()
                .get(RedisKeys.waitingQuantity(dropId), userId)
                .mapNotNull { it.toIntOrNull() }
                .defaultIfEmpty(1),
        ).awaitSingleOrNull() ?: return null
        return WaitingTicket(rank = tuple.t1, totalWaiting = tuple.t2, quantity = tuple.t3)
    }

    override suspend fun statusSnapshotOf(
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
            listOf(userId, now.toEpochMilli().toString(), if (touchHeartbeat) "1" else "0"),
        ).awaitFirstOrNull() ?: throw IllegalStateException("status-snapshot.lua가 null을 반환했습니다(dropId=$dropId)")
        return toSnapshot(flat)
    }

    private fun toSnapshot(flat: List<String>): QueueStatusSnapshot {
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

    override suspend fun markAskedIfAbsent(dropId: String, userId: String, now: Instant): Long =
        redisTemplate.execute(
            markAskedScript,
            listOf(RedisKeys.decision(dropId)),
            listOf(userId, now.toEpochMilli().toString()),
        ).awaitFirstOrNull() ?: -1

    override suspend fun sweepDecisionTimeout(dropId: String, now: Instant, timeoutMs: Long): String? =
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
            listOf(now.toEpochMilli().toString(), timeoutMs.toString()),
        ).awaitFirstOrNull()?.takeIf { it.isNotEmpty() }

    override suspend fun sizeOf(dropId: String): Long =
        redisTemplate.opsForZSet().size(RedisKeys.queue(dropId)).defaultIfEmpty(0).awaitSingle()

    override suspend fun admittedQuantityOf(dropId: String, userId: String): Int? =
        redisTemplate.opsForValue().get(RedisKeys.admission(dropId, userId)).awaitSingleOrNull()?.toIntOrNull()

    override suspend fun sweepExpired(dropId: String, now: Instant, heartbeatTtlMs: Long): Long {
        val cutoff = now.minus(heartbeatTtlMs, ChronoUnit.MILLIS)
        return redisTemplate.execute(
            sweepScript,
            listOf(
                RedisKeys.queue(dropId),
                RedisKeys.heartbeat(dropId),
                RedisKeys.waitingQuantity(dropId),
                RedisKeys.decision(dropId),
            ),
            listOf(cutoff.toEpochMilli().toString()),
        ).awaitFirstOrNull() ?: 0
    }

    override suspend fun admitBatch(dropId: String, maxScan: Int, ttlSeconds: Long): List<AdmittedEntry> {
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
            listOf(dropId, ttlSeconds.toString(), Instant.now().toEpochMilli().toString(), maxScan.toString()),
        ).awaitFirstOrNull() ?: emptyList()
        val entries = ArrayList<AdmittedEntry>(flat.size / 2)
        var i = 0
        while (i < flat.size) {
            entries.add(AdmittedEntry(userId = flat[i], quantity = flat[i + 1].toInt()))
            i += 2
        }
        return entries
    }

    override suspend fun sweepAdmittedTickets(dropId: String, now: Instant): Long =
        redisTemplate.execute(
            sweepAdmittedScript,
            listOf(RedisKeys.admitted(dropId), RedisKeys.admittedQuantity(dropId), RedisKeys.outstanding(dropId)),
            listOf(now.toEpochMilli().toString()),
        ).awaitFirstOrNull() ?: 0

    override suspend fun outstandingOf(dropId: String): Long =
        redisTemplate.opsForValue().get(RedisKeys.outstanding(dropId)).awaitSingleOrNull()?.toLongOrNull() ?: 0

    override suspend fun markWaitConfirmed(
        dropId: String,
        userId: String,
        grantableNowAtConfirm: Long,
        maxAtConfirm: Long?,
    ) {
        val value = "$WAIT_CONFIRMED_MARKER:$grantableNowAtConfirm:${maxAtConfirm?.toString() ?: NO_MAX_MARKER}"
        redisTemplate.opsForHash<String, String>()
            .put(RedisKeys.decision(dropId), userId, value)
            .awaitSingle()
    }

    override suspend fun admitSingle(dropId: String, userId: String, ttlSeconds: Long): AdmittedEntry? {
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
            listOf(dropId, userId, ttlSeconds.toString(), Instant.now().toEpochMilli().toString()),
        ).awaitFirstOrNull() ?: 0
        return if (grant > 0) AdmittedEntry(userId = userId, quantity = grant.toInt()) else null
    }

    override suspend fun removeFromQueue(dropId: String, userId: String) {
        redisTemplate.execute(
            removeFromQueueScript,
            listOf(
                RedisKeys.queue(dropId),
                RedisKeys.heartbeat(dropId),
                RedisKeys.waitingQuantity(dropId),
                RedisKeys.decision(dropId),
            ),
            listOf(userId),
        ).awaitFirstOrNull()
    }

    override suspend fun activeDropIds(): Set<String> =
        redisTemplate.opsForSet().members(RedisKeys.activeDrops()).collectList().awaitSingle().toSet()

    override suspend fun pruneIfIdle(dropId: String): Boolean {
        // 아래 두 조회는 서로 무관하니 zip으로 동시에 보낸다(예전 Reactor 버전과 동일한 왕복 수).
        val sizeMono = redisTemplate.opsForZSet().size(RedisKeys.queue(dropId)).defaultIfEmpty(0)
        val outstandingMono = redisTemplate.opsForValue().get(RedisKeys.outstanding(dropId))
            .mapNotNull { it.toLongOrNull() }.defaultIfEmpty(0)
        val sizeAndOutstanding = Mono.zip(sizeMono, outstandingMono).awaitSingle()
        if (sizeAndOutstanding.t1 > 0 || sizeAndOutstanding.t2 > 0) return false
        // 이 체크와 SREM 사이의 좁은 레이스(그 순간 다른 요청이 막 SADD)는 최악의 경우
        // 다음 스케줄러/스위퍼 tick에서 다시 SADD되어 자가치유되므로(active-drops는 권위
        // 있는 상태가 아니라 발견용 힌트일 뿐) 원자성이 필수는 아니다.
        val removed = redisTemplate.opsForSet().remove(RedisKeys.activeDrops(), dropId).awaitSingle()
        return removed == 1L
    }

    companion object {
        private const val WAIT_CONFIRMED_MARKER = "WAIT_CONFIRMED"
        private const val NO_MAX_MARKER = "NA"
        private const val ASKED_PREFIX = "ASKED:"

        /** status-snapshot.lua의 "부재" 센티널 - Lua 멀티불크는 nil을 표현할 수 없어 '-'로 채운다. */
        private const val ABSENT = "-"
    }
}
