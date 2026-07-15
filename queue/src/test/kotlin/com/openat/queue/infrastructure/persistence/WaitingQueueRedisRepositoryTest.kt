package com.openat.queue.infrastructure.persistence

import com.openat.queue.domain.model.AdmittedEntry
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
 * `drop:{dropId}` 해시는 실제로는 product가 소유하지만, 여기서는 그 계약을 흉내내 테스트가
 * 직접 seed한다(정상 - WaitingQueueRedisRepository는 이 키를 읽기 전용으로만 다룬다).
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

        val result = repository.enqueueOrFastAdmit(dropId, userId, 3, TTL_SECONDS, Instant.now())

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

        val result = repository.enqueueOrFastAdmit(dropId, userId, 5, TTL_SECONDS, Instant.now())

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
        val dropId = newDropId() // drop:{dropId} 해시를 seed하지 않음 - 캐시 미존재 상태
        val userId = "user-1"

        val result = repository.enqueueOrFastAdmit(dropId, userId, 1, TTL_SECONDS, Instant.now())

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

        val result = repository.enqueueOrFastAdmit(dropId, "newcomer", 1, TTL_SECONDS, Instant.now())

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
                    val result = repository.enqueueOrFastAdmit(dropId, "user-$i", 1, TTL_SECONDS, Instant.now())
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
        // 같은 밀리초에 enqueue되면 ZSET score가 같아져 사전순("back-user" < "front-user")으로
        // 순위가 뒤집힌다 - 명시적으로 다른 타임스탬프를 줘서 줄 선 순서를 결정적으로 만든다.
        val t0 = Instant.now()
        repository.enqueueOrFastAdmit(dropId, front, 5, TTL_SECONDS, t0)
        repository.enqueueOrFastAdmit(dropId, back, 1, TTL_SECONDS, t0.plusMillis(1))
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
        val t0 = Instant.now()
        repository.enqueueOrFastAdmit(dropId, front, 5, TTL_SECONDS, t0)
        repository.enqueueOrFastAdmit(dropId, back, 1, TTL_SECONDS, t0.plusMillis(1))
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
        val t0 = Instant.now()
        repository.enqueueOrFastAdmit(dropId, front, 5, TTL_SECONDS, t0)
        repository.enqueueOrFastAdmit(dropId, back, 1, TTL_SECONDS, t0.plusMillis(1))
        seedRemaining(dropId, 3)

        assertThat(repository.admitSingle(dropId, back, TTL_SECONDS)).isNull()
        assertThat(repository.admittedQuantityOf(dropId, back)).isNull()

        val granted = repository.admitSingle(dropId, front, TTL_SECONDS)

        assertThat(granted).isNotNull
        assertThat(granted!!.quantity).isEqualTo(3) // min(요청 5, 가용 3)
        assertThat(repository.admittedQuantityOf(dropId, front)).isEqualTo(3)
    }

    @Test
    @DisplayName("완전히 유휴 상태가 되면 pruneIfIdle이 active-drops에서 제거한다")
    fun pruneIfIdle_removesOnlyWhenFullyIdle() {
        val dropId = newDropId()
        seedRemaining(dropId, 10)
        val userId = "user-1"
        repository.enqueueOrFastAdmit(dropId, userId, 1, TTL_SECONDS, Instant.now())
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

    private fun seedRemaining(dropId: String, remaining: Long) {
        redisTemplate.opsForHash<String, String>().put(RedisKeys.productDrop(dropId), "remaining", remaining.toString())
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
