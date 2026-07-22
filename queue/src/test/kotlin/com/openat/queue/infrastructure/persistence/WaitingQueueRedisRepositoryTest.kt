package com.openat.queue.infrastructure.persistence

import com.openat.queue.domain.model.AdmittedEntry
import com.openat.queue.domain.model.DecisionState
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * `enqueue-or-admit.lua`(즉시 입장 fast path)를 실제 Redis로 검증한다. product의
 * `DropCacheRedisAdaptorTest`와 동일한 관례(Testcontainers `redis:7-alpine`, Spring 컨텍스트
 * 없이 어댑터를 직접 구성) - queue 모듈에 생기는 첫 테스트.
 *
 * `remaining`은 이제 product의 `drop:{dropId}` 해시가 아니라 `total(dropId) - reserved(dropId)`로
 * 계산된다(queue-remaining-sync 재설계 작업 참고) - `seedRemaining()`은 `reserved`를 건드리지
 * 않고(기본 0) `total`만 세팅해 "그만큼 자유 재고가 있다"를 흉내낸다.
 */
@Testcontainers
@DisplayName("대기열(Redis) 저장소 - 즉시 입장 fast path")
class WaitingQueueRedisRepositoryTest {

    @Test
    @DisplayName("대기열이 비어 있고 재고가 충분하면 즉시 입장권을 발급하고 대기열에는 등록하지 않는다")
    fun enqueueOrFastAdmit_emptyQueueWithStock_admitsImmediately() {
        val dropId = newDropId()
        val userId = "user-1"
        seedRemaining(dropId, 10)

        val result = repository.enqueueOrFastAdmit(dropId, userId, 3, TTL_SECONDS)

        assertThat(result).isNotNull
        assertThat(result!!.userId).isEqualTo(userId)
        assertThat(result.quantity).isEqualTo(3)
        assertThat(repository.sizeOf(dropId)).isZero()
        assertThat(repository.admittedQuantityOf(dropId, userId)).isEqualTo(3)
        assertThat(repository.activeDropIds()).contains(dropId)
    }

    @Test
    @DisplayName("대기열이 비어 있어도 재고가 부족하면 대기열에 등록하고 입장권을 발급하지 않는다")
    fun enqueueOrFastAdmit_emptyQueueInsufficientStock_fallsBackToEnqueue() {
        val dropId = newDropId()
        val userId = "user-1"
        seedRemaining(dropId, 2)

        val result = repository.enqueueOrFastAdmit(dropId, userId, 5, TTL_SECONDS)

        assertThat(result).isNull()
        assertThat(repository.admittedQuantityOf(dropId, userId)).isNull()
        val ticket = repository.ticketOf(dropId, userId)
        assertThat(ticket).isNotNull
        assertThat(ticket!!.rank).isZero()
        assertThat(ticket.totalWaiting).isEqualTo(1)
        assertThat(ticket.quantity).isEqualTo(5)
        assertThat(repository.activeDropIds()).contains(dropId)
    }

    @Test
    @DisplayName("재고 캐시가 아직 워밍되지 않았으면 안전하게 대기열에 등록한다")
    fun enqueueOrFastAdmit_stockNotWarmedYet_fallsBackToEnqueue() {
        val dropId = newDropId() // total:{dropId}를 seed하지 않음 - 부트스트랩 캐시 미존재 상태
        val userId = "user-1"

        val result = repository.enqueueOrFastAdmit(dropId, userId, 1, TTL_SECONDS)

        assertThat(result).isNull()
        assertThat(repository.ticketOf(dropId, userId)).isNotNull()
    }

    @Test
    @DisplayName("이미 대기 중인 사람이 있으면 재고가 충분해도 새치기 없이 대기열에 등록한다")
    fun enqueueOrFastAdmit_nonEmptyQueue_neverCutsInLine() {
        val dropId = newDropId()
        seedRemaining(dropId, 10)
        // "이미 대기 중"을 직접 흉내낸다(실제로는 재고 부족으로 대기하게 된 사람일 것).
        redisTemplate.opsForZSet().add(RedisKeys.queue(dropId), "already-waiting", 0.0)

        val result = repository.enqueueOrFastAdmit(dropId, "newcomer", 1, TTL_SECONDS)

        assertThat(result).isNull()
        assertThat(repository.admittedQuantityOf(dropId, "newcomer")).isNull()
        assertThat(repository.ticketOf(dropId, "newcomer")).isNotNull()
    }

    @Test
    @DisplayName("동시 즉시입장 요청에도 재고를 초과해 발급하지 않는다")
    fun enqueueOrFastAdmit_concurrentRequests_neverOversells() {
        val dropId = newDropId()
        val stock = 100
        val requests = 300
        seedRemaining(dropId, stock.toLong())
        val executor = Executors.newFixedThreadPool(32)
        val done = CountDownLatch(requests)
        val admittedCount = AtomicInteger()

        repeat(requests) { i ->
            executor.submit {
                try {
                    val result = repository.enqueueOrFastAdmit(dropId, "user-$i", 1, TTL_SECONDS)
                    if (result != null) {
                        admittedCount.incrementAndGet()
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        done.await()
        executor.shutdown()

        assertThat(admittedCount.get()).isEqualTo(stock)
        assertThat(repository.outstandingOf(dropId)).isEqualTo(stock.toLong())
        // 재고를 넘는 나머지는 전부 대기열로 떨어졌어야 한다(발급도 안 되고 유실도 안 됨).
        assertThat(repository.sizeOf(dropId)).isEqualTo((requests - stock).toLong())
    }

    @Test
    @DisplayName("맨 앞사람 몫이 재고로 안 되면, 뒷사람 몫이 재고로 충분해도 새치기 입장시키지 않는다(엄격한 FIFO)")
    fun admitBatch_frontCandidateBlocked_neverAdmitsSmallerCandidateBehind() {
        val dropId = newDropId()
        val front = "front-user"
        val back = "back-user"
        seedRemaining(dropId, 0) // 아무도 즉시 입장 못 하게 해서 둘 다 대기열에 줄서게 한다
        // 순번(score)은 이제 enqueue-or-admit.lua가 Redis 자신의 TIME()으로 마이크로초
        // 해상도로 찍으므로, 두 번의 별도 왕복(각각 실제 네트워크 호출)이면 자연히 순서가
        // 보장된다 - 예전처럼 앱에서 타임스탬프를 강제로 벌려줄 필요가 없다.
        repository.enqueueOrFastAdmit(dropId, front, 5, TTL_SECONDS)
        repository.enqueueOrFastAdmit(dropId, back, 1, TTL_SECONDS)
        seedRemaining(dropId, 3) // 앞사람(5개)은 부족하지만 뒷사람(1개)은 충분한 재고

        val admitted = repository.admitBatch(dropId, MAX_SCAN, TTL_SECONDS)

        assertThat(admitted).isEmpty()
        assertThat(repository.admittedQuantityOf(dropId, back)).isNull()
        assertThat(repository.admittedQuantityOf(dropId, front)).isNull()
        assertThat(repository.ticketOf(dropId, front)).isNotNull()
        assertThat(repository.ticketOf(dropId, back)).isNotNull()
    }

    @Test
    @DisplayName("맨 앞사람이 대기열을 떠나면(포기) 다음 admitBatch에서 뒷사람이 정상 입장한다")
    fun admitBatch_afterFrontLeaves_backGetsAdmittedOnNextTick() {
        val dropId = newDropId()
        val front = "front-user"
        val back = "back-user"
        seedRemaining(dropId, 0)
        repository.enqueueOrFastAdmit(dropId, front, 5, TTL_SECONDS)
        repository.enqueueOrFastAdmit(dropId, back, 1, TTL_SECONDS)
        seedRemaining(dropId, 3)
        assertThat(repository.admitBatch(dropId, MAX_SCAN, TTL_SECONDS)).isEmpty() // 여전히 막혀 있음 확인

        repository.removeFromQueue(dropId, front) // "포기(GIVE_UP)" 시뮬레이션 - 차단 해제

        val admitted = repository.admitBatch(dropId, MAX_SCAN, TTL_SECONDS)

        assertThat(admitted).containsExactly(AdmittedEntry(back, 1))
        assertThat(repository.admittedQuantityOf(dropId, back)).isEqualTo(1)
    }

    @Test
    @DisplayName("맨 앞(rank 0)이 아닌 사용자는 PARTIAL(admitSingle)이 거부되고, 맨 앞 사용자만 허용된다")
    fun admitSingle_onlyFrontRankCanPartial() {
        val dropId = newDropId()
        val front = "front-user"
        val back = "back-user"
        seedRemaining(dropId, 0)
        repository.enqueueOrFastAdmit(dropId, front, 5, TTL_SECONDS)
        repository.enqueueOrFastAdmit(dropId, back, 1, TTL_SECONDS)
        seedRemaining(dropId, 3)

        assertThat(repository.admitSingle(dropId, back, TTL_SECONDS)).isNull()
        assertThat(repository.admittedQuantityOf(dropId, back)).isNull()

        val granted = repository.admitSingle(dropId, front, TTL_SECONDS)

        assertThat(granted).isNotNull
        assertThat(granted!!.quantity).isEqualTo(3) // min(요청 5, 가용 3)
        assertThat(repository.admittedQuantityOf(dropId, front)).isEqualTo(3)
    }

    @Test
    @DisplayName("마감(closeAt 경과)된 드롭은 대기열이 비고 재고가 있어도 fast path로 입장시키지 않는다")
    fun enqueueOrFastAdmit_closedDrop_neverFastAdmits() {
        val dropId = newDropId()
        seedRemaining(dropId, 10)
        redisTemplate.opsForHash<String, String>()
            .put(RedisKeys.dropMeta(dropId), "closeAt", Instant.now().minusSeconds(60).toEpochMilli().toString())

        val result = repository.enqueueOrFastAdmit(dropId, "user-1", 1, TTL_SECONDS)

        assertThat(result).isNull()
        assertThat(repository.admittedQuantityOf(dropId, "user-1")).isNull()
    }

    @Test
    @DisplayName(
        "사전순으로 뒤집히는 userId를 써도 먼저 등록된 쪽이 항상 낮은 순번을 받는다" +
            "(TIME() 기반 순번 - 밀리초 타이 시 사전순으로 순위가 뒤집히던 버그의 회귀 테스트)",
    )
    fun enqueueOrFastAdmit_rankReflectsInsertionOrder_notUserIdLexicalOrder() {
        val dropId = newDropId()
        seedRemaining(dropId, 0) // 즉시입장 막아서 둘 다 대기열로 떨어지게 한다

        // 사전순으로는 "zzz-user"가 "aaa-user"보다 뒤에 온다 - 예전 버그(앱 서버가 계산한
        // Instant.now() 밀리초가 타이 나면 Redis가 member 문자열 사전순으로 정렬)였다면
        // zzz-user를 먼저 넣어도 aaa-user에게 순번을 뺏길 수 있었다. enqueue-or-admit.lua가
        // 이제 Redis 자신의 TIME()(마이크로초 해상도)으로 원자적으로 순번을 찍으므로, 두
        // 번의 별도 왕복이면 등록 순서가 사전순과 무관하게 그대로 보존돼야 한다.
        repository.enqueueOrFastAdmit(dropId, "zzz-user", 1, TTL_SECONDS)
        repository.enqueueOrFastAdmit(dropId, "aaa-user", 1, TTL_SECONDS)

        assertThat(repository.ticketOf(dropId, "zzz-user")!!.rank).isEqualTo(0)
        assertThat(repository.ticketOf(dropId, "aaa-user")!!.rank).isEqualTo(1)
    }

    @Test
    @DisplayName("statusSnapshotOf는 대기자의 순번/수량/재고/결정상태를 원자 스냅샷 하나로 반환하고 하트비트도 갱신한다")
    fun statusSnapshotOf_waitingUser_returnsConsistentSnapshotAndTouchesHeartbeat() {
        val dropId = newDropId()
        val userId = "user-1"
        seedRemaining(dropId, 0)
        repository.enqueueOrFastAdmit(dropId, userId, 3, TTL_SECONDS)
        // remaining = total - reserved이므로, remaining=2와 reserved=7을 동시에 원하면
        // total=9여야 한다(2+7) - seedRemaining(total만 세팅, reserved=0 가정)을 안 쓰고
        // 여기서 직접 둘 다 세팅한다.
        redisTemplate.opsForValue().set(RedisKeys.total(dropId), "9")
        redisTemplate.opsForValue().set(RedisKeys.reserved(dropId), "7")
        val pollAt = Instant.now()

        val snap = repository.statusSnapshotOf(dropId, userId, pollAt, touchHeartbeat = true)

        assertThat(snap.admittedQuantity).isNull()
        assertThat(snap.rank).isEqualTo(0)
        assertThat(snap.totalWaiting).isEqualTo(1)
        assertThat(snap.quantity).isEqualTo(3)
        assertThat(snap.remaining).isEqualTo(2)
        assertThat(snap.outstanding).isEqualTo(0)
        assertThat(snap.reserved).isEqualTo(7)
        assertThat(snap.decision).isNull()
        // 하트비트가 폴링 시각으로 갱신됐는지(이탈 판정 기준점이 뒤로 밀렸는지) 확인.
        val heartbeatScore = redisTemplate.opsForZSet().score(RedisKeys.heartbeat(dropId), userId)
        assertThat(heartbeatScore!!.toLong()).isEqualTo(pollAt.toEpochMilli())
    }

    @Test
    @DisplayName("statusSnapshotOf는 미소진 입장권 보유자(READY)를 입장 수량과 함께 조기 판별한다")
    fun statusSnapshotOf_admittedUser_returnsReadyQuantity() {
        val dropId = newDropId()
        val userId = "user-1"
        seedRemaining(dropId, 10)
        repository.enqueueOrFastAdmit(dropId, userId, 3, TTL_SECONDS) // 즉시 입장

        val snap = repository.statusSnapshotOf(dropId, userId, Instant.now(), touchHeartbeat = true)

        assertThat(snap.admittedQuantity).isEqualTo(3)
        assertThat(snap.rank).isNull()
    }

    @Test
    @DisplayName("markAskedIfAbsent는 최초 시각을 보존하고(마감이 밀리지 않음), WAIT 확정자에겐 -1을 반환한다")
    fun markAskedIfAbsent_preservesFirstAskedAtAndSkipsWaitConfirmed() {
        val dropId = newDropId()
        val userId = "user-1"
        seedRemaining(dropId, 0)
        repository.enqueueOrFastAdmit(dropId, userId, 5, TTL_SECONDS)
        val t0 = Instant.now()

        val first = repository.markAskedIfAbsent(dropId, userId, t0)
        val second = repository.markAskedIfAbsent(dropId, userId, t0.plusSeconds(10))

        assertThat(first).isEqualTo(t0.toEpochMilli())
        assertThat(second).isEqualTo(first) // 재폴링해도 askedAt(=마감 기준점)은 그대로

        repository.markWaitConfirmed(dropId, userId, grantableNowAtConfirm = 0L, maxAtConfirm = 1L)
        assertThat(repository.markAskedIfAbsent(dropId, userId, t0.plusSeconds(20))).isEqualTo(-1)
    }

    @Test
    @DisplayName("무응답 결정자(ASKED + 타임아웃 경과 + 여전히 재고 부족)는 sweepDecisionTimeout이 대기열에서 제거한다")
    fun sweepDecisionTimeout_removesUnresponsiveFront() {
        val dropId = newDropId()
        val front = "front-user"
        val back = "back-user"
        seedRemaining(dropId, 0)
        val t0 = Instant.now()
        repository.enqueueOrFastAdmit(dropId, front, 5, TTL_SECONDS)
        repository.enqueueOrFastAdmit(dropId, back, 1, TTL_SECONDS)
        seedRemaining(dropId, 3) // front(5개)는 부족 → DECISION_REQUIRED 대상
        repository.markAskedIfAbsent(dropId, front, t0)

        // 타임아웃 전에는 제거하지 않는다.
        assertThat(repository.sweepDecisionTimeout(dropId, t0.plusMillis(29_999), 30_000)).isNull()
        assertThat(repository.ticketOf(dropId, front)).isNotNull()

        // 타임아웃 경과 - 무응답 이탈 처리되고, 뒷사람이 rank 0으로 승격돼 다음 tick에 입장 가능해진다.
        val removed = repository.sweepDecisionTimeout(dropId, t0.plusMillis(30_000), 30_000)

        assertThat(removed).isEqualTo(front)
        assertThat(repository.ticketOf(dropId, front)).isNull()
        assertThat(repository.ticketOf(dropId, back)!!.rank).isEqualTo(0)
        assertThat(repository.admitBatch(dropId, MAX_SCAN, TTL_SECONDS)).containsExactly(AdmittedEntry(back, 1))
    }

    @Test
    @DisplayName("타임아웃이 지났어도 그 사이 재고가 도착해 몫이 채워졌으면 제거하지 않는다(억울한 제거 방지)")
    fun sweepDecisionTimeout_sparesFrontWhoseShareArrived() {
        val dropId = newDropId()
        val front = "front-user"
        seedRemaining(dropId, 0)
        val t0 = Instant.now()
        repository.enqueueOrFastAdmit(dropId, front, 5, TTL_SECONDS)
        seedRemaining(dropId, 3)
        repository.markAskedIfAbsent(dropId, front, t0)
        seedRemaining(dropId, 5) // 결정을 기다리는 사이 재고 도착 - 이제 몫이 채워짐

        val removed = repository.sweepDecisionTimeout(dropId, t0.plusMillis(60_000), 30_000)

        assertThat(removed).isNull()
        assertThat(repository.ticketOf(dropId, front)).isNotNull() // 남아서 다음 admit tick에 정상 입장
        // 결정 상태(ASKED)는 해소돼 있어야 한다 - 상황이 풀렸으므로.
        val snap = repository.statusSnapshotOf(dropId, front, Instant.now(), touchHeartbeat = false)
        assertThat(snap.decision).isNull()
    }

    @Test
    @DisplayName("WAIT을 명시적으로 선택한 사람은 시간이 얼마나 지나도 제거하지 않는다(정책)")
    fun sweepDecisionTimeout_neverRemovesWaitConfirmed() {
        val dropId = newDropId()
        val front = "front-user"
        seedRemaining(dropId, 0)
        val t0 = Instant.now()
        repository.enqueueOrFastAdmit(dropId, front, 5, TTL_SECONDS)
        seedRemaining(dropId, 3)
        repository.markWaitConfirmed(dropId, front, grantableNowAtConfirm = 0L, maxAtConfirm = 3L)

        val removed = repository.sweepDecisionTimeout(dropId, t0.plusSeconds(3_600), 30_000)

        assertThat(removed).isNull()
        assertThat(repository.ticketOf(dropId, front)).isNotNull()
    }

    @Test
    @DisplayName("markWaitConfirmed가 기록한 (grantableNow, maxAtConfirm)이 statusSnapshotOf로 그대로 왕복된다(null도 포함)")
    fun markWaitConfirmed_roundTripsGrantableNowAndMaxAtConfirmThroughSnapshot() {
        val dropId = newDropId()
        val withMax = "user-with-max"
        val withoutMax = "user-without-max"
        // 재고 0으로 즉시 입장을 막아 대기열에 남게 한다 - 입장권을 받으면 status-snapshot.lua가
        // decision 해시를 아예 안 보고 조기 반환하므로(READY 조기 반환 분기), 대기 상태에서만
        // decision 왕복을 확인할 수 있다.
        seedRemaining(dropId, 0)
        repository.enqueueOrFastAdmit(dropId, withMax, 1, TTL_SECONDS)
        repository.enqueueOrFastAdmit(dropId, withoutMax, 1, TTL_SECONDS)

        repository.markWaitConfirmed(dropId, withMax, grantableNowAtConfirm = 2L, maxAtConfirm = 4L)
        repository.markWaitConfirmed(dropId, withoutMax, grantableNowAtConfirm = 0L, maxAtConfirm = null)

        val snapWithMax = repository.statusSnapshotOf(dropId, withMax, Instant.now(), touchHeartbeat = false)
        val snapWithoutMax = repository.statusSnapshotOf(dropId, withoutMax, Instant.now(), touchHeartbeat = false)

        assertThat(snapWithMax.decision).isEqualTo(DecisionState.WaitConfirmed(2L, 4L))
        assertThat(snapWithoutMax.decision).isEqualTo(DecisionState.WaitConfirmed(0L, null))
    }

    @Test
    @DisplayName("완전히 유휴 상태가 되면 pruneIfIdle이 active-drops에서 제거한다")
    fun pruneIfIdle_removesOnlyWhenFullyIdle() {
        val dropId = newDropId()
        seedRemaining(dropId, 10)
        val userId = "user-1"
        repository.enqueueOrFastAdmit(dropId, userId, 1, TTL_SECONDS)
        assertThat(repository.activeDropIds()).contains(dropId)

        // 미소진 입장권이 남아있는 동안은 제거되지 않는다.
        assertThat(repository.pruneIfIdle(dropId)).isFalse()
        assertThat(repository.activeDropIds()).contains(dropId)

        // 입장권을 "소진"시킨 상태를 흉내낸다(실제로는 게이트웨이의 GETDEL + outstanding 반환).
        redisTemplate.delete(RedisKeys.admission(dropId, userId))
        redisTemplate.opsForZSet().remove(RedisKeys.admitted(dropId), userId)
        redisTemplate.opsForHash<String, String>().delete(RedisKeys.admittedQuantity(dropId), userId)
        redisTemplate.opsForValue().decrement(RedisKeys.outstanding(dropId), 1)

        assertThat(repository.pruneIfIdle(dropId)).isTrue()
        assertThat(repository.activeDropIds()).doesNotContain(dropId)
    }

    // remaining = total - reserved이므로, reserved를 안 건드리는(0 가정) 대부분의 테스트는
    // total만 세팅하면 "자유 재고 remaining개"를 그대로 흉내낼 수 있다.
    private fun seedRemaining(dropId: String, remaining: Long) {
        redisTemplate.opsForValue().set(RedisKeys.total(dropId), remaining.toString())
    }

    private fun newDropId(): String = "drop-" + java.util.UUID.randomUUID()

    companion object {
        private const val TTL_SECONDS = 60L
        private const val MAX_SCAN = 50

        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)

        private lateinit var connectionFactory: LettuceConnectionFactory
        private lateinit var redisTemplate: StringRedisTemplate
        private lateinit var repository: WaitingQueueRedisRepository

        @BeforeAll
        @JvmStatic
        fun init() {
            connectionFactory = LettuceConnectionFactory(redis.host, redis.getMappedPort(6379))
            connectionFactory.afterPropertiesSet()
            redisTemplate = StringRedisTemplate(connectionFactory)
            redisTemplate.afterPropertiesSet()
            repository = WaitingQueueRedisRepository(redisTemplate)
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            connectionFactory.destroy()
        }
    }

    @BeforeEach
    fun flush() {
        redisTemplate.execute(
            RedisCallback<Any?> { connection ->
                connection.serverCommands().flushAll()
                null
            },
        )
    }
}
