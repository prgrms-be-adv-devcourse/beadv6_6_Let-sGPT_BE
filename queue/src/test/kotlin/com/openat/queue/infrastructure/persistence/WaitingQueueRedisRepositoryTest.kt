package com.openat.queue.infrastructure.persistence

import com.openat.queue.domain.model.AdmittedEntry
import com.openat.queue.domain.model.DecisionState
import com.openat.queue.infrastructure.trace.QueueTraceBridge
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.RedisCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * `enqueue-or-admit.lua`(즉시 입장 fast path) 등 8개 Lua 스크립트를 실제 Redis로 검증한다.
 * product의 `DropCacheRedisAdaptorTest`와 동일한 관례(Testcontainers `redis:7-alpine`, Spring
 * 컨텍스트 없이 어댑터를 직접 구성).
 *
 * `remaining`은 이제 product의 `drop:{dropId}` 해시가 아니라 `total(dropId) - reserved(dropId)`로
 * 계산된다(queue-remaining-sync 재설계 작업 참고) - `seedRemaining()`은 `reserved`를 건드리지
 * 않고(기본 0) `total`만 세팅해 "그만큼 자유 재고가 있다"를 흉내낸다.
 *
 * feature/queue-remaining-sync(코루틴 전환): [WaitingQueueRedisRepository]가 `suspend fun`을
 * 돌려주므로 각 테스트를 `runBlocking { }`으로 감싼다(구독해야 실제로 실행되는 리액티브 스트림
 * 규약과 달리, suspend 함수는 그냥 호출하면 그 자리에서 실행된다 - `.block()` 자체가 필요 없다).
 * 동시성 테스트([enqueueOrFastAdmit_concurrentRequests_neverOversells])는 여러 스레드에서
 * 각자 `runBlocking { }`으로 진입한다(스레드 하나당 코루틴 하나, 예전 `.block()`과 동등).
 * 시딩/직접 검증용 헬퍼는 여전히 블로킹 `StringRedisTemplate`을 그대로 쓴다(같은 커넥션
 * 팩토리에서 두 템플릿을 다 만든다) - 테스트 코드 자체를 리액티브로 바꿀 이유가 없다.
 */
@Testcontainers
@DisplayName("대기열(Redis) 저장소 - 즉시 입장 fast path")
class WaitingQueueRedisRepositoryTest {

    @Test
    @DisplayName("대기열이 비어 있고 재고가 충분하면 즉시 입장권을 발급하고 대기열에는 등록하지 않는다")
    fun enqueueOrFastAdmit_emptyQueueWithStock_admitsImmediately() = runBlocking<Unit> {
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
    fun enqueueOrFastAdmit_emptyQueueInsufficientStock_fallsBackToEnqueue() = runBlocking<Unit> {
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
    fun enqueueOrFastAdmit_stockNotWarmedYet_fallsBackToEnqueue() = runBlocking<Unit> {
        val dropId = newDropId() // total:{dropId}를 seed하지 않음 - 부트스트랩 캐시 미존재 상태
        val userId = "user-1"

        val result = repository.enqueueOrFastAdmit(dropId, userId, 1, TTL_SECONDS)

        assertThat(result).isNull()
        assertThat(repository.ticketOf(dropId, userId)).isNotNull()
    }

    @Test
    @DisplayName("이미 대기 중인 사람이 있으면 재고가 충분해도 새치기 없이 대기열에 등록한다")
    fun enqueueOrFastAdmit_nonEmptyQueue_neverCutsInLine() = runBlocking<Unit> {
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
                    val result = runBlocking { repository.enqueueOrFastAdmit(dropId, "user-$i", 1, TTL_SECONDS) }
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
        runBlocking {
            assertThat(repository.outstandingOf(dropId)).isEqualTo(stock.toLong())
            // 재고를 넘는 나머지는 전부 대기열로 떨어졌어야 한다(발급도 안 되고 유실도 안 됨).
            assertThat(repository.sizeOf(dropId)).isEqualTo((requests - stock).toLong())
        }
    }

    @Test
    @DisplayName(
        "같은 사용자가 진짜 동시에 즉시입장을 두 번 요청해도 outstanding이 중복 가산되지 않는다" +
            "(앱 레벨 admittedQuantityOf 가드와 이 스크립트 호출 사이의 좁은 레이스 방어)"
    )
    fun enqueueOrFastAdmit_sameUserConcurrentRequests_neverDoubleCountsOutstanding() {
        // QueueService.enter()는 호출 전 admittedQuantityOf로 이미 입장권을 보유했는지 먼저
        // 확인하지만, 그 GET과 이 스크립트 호출 사이엔 왕복(round trip)이 있어 완전한 원자성이
        // 아니다 - 실제로 같은 순간 도착한 두 요청은 둘 다 "아직 없음"을 보고 여기까지 올 수
        // 있다. 이 리포지토리 테스트는 그 앱 레벨 가드를 건너뛰고 같은 사용자로 동시에 스크립트를
        // 직접 두 번 호출해, 스크립트 자체(Redis의 단일 스레드 직렬 실행 + 이 안의 ZSCORE 가드)가
        // 그 레이스를 닫는지 검증한다.
        val dropId = newDropId()
        val userId = "racer"
        seedRemaining(dropId, 10)
        val executor = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val results = java.util.Collections.synchronizedList(mutableListOf<AdmittedEntry?>())

        repeat(2) {
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    results.add(runBlocking { repository.enqueueOrFastAdmit(dropId, userId, 1, TTL_SECONDS) })
                } finally {
                    done.countDown()
                }
            }
        }
        ready.await()
        start.countDown()
        done.await()
        executor.shutdown()

        // 둘 다 도착은 했지만, 발급은 정확히 한 번만 이뤄져야 한다 - 두 번째 호출은 이미
        // admitted ZSET에 있는 걸 보고 fast admit을 재부여하지 않고, 대기열에도 등록하지
        // 않은 채 그대로 반환한다(아래 좀비 대기열 항목 회귀 테스트 참고 - 대기열에 등록하는
        // 폴백은 "지연된 이중 입장" 결함으로 이어져 폐기했다).
        assertThat(results.count { it != null }).isEqualTo(1)
        runBlocking {
            assertThat(repository.admittedQuantityOf(dropId, userId)).isEqualTo(1)
            assertThat(repository.outstandingOf(dropId)).isEqualTo(1L)
            assertThat(repository.sizeOf(dropId)).isZero()
            assertThat(repository.ticketOf(dropId, userId)).isNull()
        }
    }

    @Test
    @DisplayName(
        "이미 입장권을 보유한 사용자의 중복 요청은 대기열에 좀비 항목을 남기지 않는다" +
            "(남기면 원래 티켓을 소비한 뒤 admit.lua가 그 항목을 재입장시켜 지연된 이중 입장이 된다)"
    )
    fun enqueueOrFastAdmit_secondCallForAlreadyAdmittedUser_neverResultsInDelayedReAdmission() = runBlocking<Unit> {
        val dropId = newDropId()
        val userId = "double-clicker"
        seedRemaining(dropId, 5)

        // 1번째 요청: 정상적으로 즉시 입장한다(대기열이 비어 있고 재고 충분).
        val first = repository.enqueueOrFastAdmit(dropId, userId, 1, TTL_SECONDS)
        assertThat(first).isNotNull

        // 2번째 요청(같은 사용자 - 더블클릭/중복 탭 등으로 재전송됐다고 가정): 이미 입장권을
        // 보유 중이므로 아무 효과가 없어야 한다 - 특히 대기열에 좀비 항목을 남기면 안 된다.
        val second = repository.enqueueOrFastAdmit(dropId, userId, 1, TTL_SECONDS)
        assertThat(second).isNull()
        assertThat(repository.sizeOf(dropId)).isZero()
        assertThat(repository.ticketOf(dropId, userId)).isNull()

        // 1번째 티켓을 "소비"한 상태를 흉내낸다(실제로는 게이트웨이의 GETDEL +
        // release-admitted-tracking.lua가 주문 성공 응답 시점에 admitted 추적만 즉시 정리하고,
        // outstanding은 이후 order의 CREATED 이벤트 컨슘 시점에 별도로 차감된다 - 이 테스트는
        // admitted 추적 정리만으로 재현에 충분하므로 outstanding도 함께 맞춰 정리한다).
        redisTemplate.delete(RedisKeys.admission(dropId, userId))
        redisTemplate.opsForZSet().remove(RedisKeys.admitted(dropId), userId)
        redisTemplate.opsForHash<String, String>().delete(RedisKeys.admittedQuantity(dropId), userId)
        redisTemplate.opsForValue().decrement(RedisKeys.outstanding(dropId), 1)
        assertThat(repository.admittedQuantityOf(dropId, userId)).isNull()

        // 다음 admit tick: 대기열에 좀비 항목이 없으므로 이 사용자에게 재고를 또 내주지 않는다.
        val admitted = repository.admitBatch(dropId, MAX_SCAN, TTL_SECONDS)

        assertThat(admitted).isEmpty()
        assertThat(repository.admittedQuantityOf(dropId, userId)).isNull()
    }

    @Test
    @DisplayName("맨 앞사람 몫이 재고로 안 되면, 뒷사람 몫이 재고로 충분해도 새치기 입장시키지 않는다(엄격한 FIFO)")
    fun admitBatch_frontCandidateBlocked_neverAdmitsSmallerCandidateBehind() = runBlocking<Unit> {
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
    fun admitBatch_afterFrontLeaves_backGetsAdmittedOnNextTick() = runBlocking<Unit> {
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
    fun admitSingle_onlyFrontRankCanPartial() = runBlocking<Unit> {
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
    fun enqueueOrFastAdmit_closedDrop_neverFastAdmits() = runBlocking<Unit> {
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
    fun enqueueOrFastAdmit_rankReflectsInsertionOrder_notUserIdLexicalOrder() = runBlocking<Unit> {
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
    fun statusSnapshotOf_waitingUser_returnsConsistentSnapshotAndTouchesHeartbeat() = runBlocking<Unit> {
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
    fun statusSnapshotOf_admittedUser_returnsReadyQuantity() = runBlocking<Unit> {
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
    fun markAskedIfAbsent_preservesFirstAskedAtAndSkipsWaitConfirmed() = runBlocking<Unit> {
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
    fun sweepDecisionTimeout_removesUnresponsiveFront() = runBlocking<Unit> {
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
    fun sweepDecisionTimeout_sparesFrontWhoseShareArrived() = runBlocking<Unit> {
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
    fun sweepDecisionTimeout_neverRemovesWaitConfirmed() = runBlocking<Unit> {
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
    fun markWaitConfirmed_roundTripsGrantableNowAndMaxAtConfirmThroughSnapshot() = runBlocking<Unit> {
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
        val snapWithoutMax =
            repository.statusSnapshotOf(dropId, withoutMax, Instant.now(), touchHeartbeat = false)

        assertThat(snapWithMax.decision).isEqualTo(DecisionState.WaitConfirmed(2L, 4L))
        assertThat(snapWithoutMax.decision).isEqualTo(DecisionState.WaitConfirmed(0L, null))
    }

    @Test
    @DisplayName("완전히 유휴 상태가 되면 pruneIfIdle이 active-drops에서 제거한다")
    fun pruneIfIdle_removesOnlyWhenFullyIdle() = runBlocking<Unit> {
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

    @Test
    @DisplayName("READY 사용자가 포기하면 입장권을 즉시 반납하고 outstanding이 그만큼 되돌아간다")
    fun releaseAdmission_admittedUser_returnsQuantityAndDecrementsOutstanding() = runBlocking<Unit> {
        val dropId = newDropId()
        val userId = "ready-user"
        seedRemaining(dropId, 10)
        repository.enqueueOrFastAdmit(dropId, userId, 3, TTL_SECONDS) // 즉시 입장 - 입장권 발급
        assertThat(outstandingOf(dropId)).isEqualTo(3)

        val released = repository.releaseAdmission(dropId, userId, TOMBSTONE_TTL_SECONDS)

        assertThat(released).isEqualTo(3)
        assertThat(outstandingOf(dropId)).isZero()
        // status-snapshot.lua가 READY 판정에 쓰는 키가 사라져야 다음 폴링에서 READY가 풀린다.
        assertThat(repository.admittedQuantityOf(dropId, userId)).isNull()
        assertThat(admittedScoreOf(dropId, userId)).isNull()
    }

    @Test
    @DisplayName("이미 소진된 입장권(게이트웨이 GETDEL 이후)이면 아무 것도 하지 않는다 - outstanding 이중 차감 방지")
    fun releaseAdmission_alreadyConsumedTicket_isNoOp() = runBlocking<Unit> {
        val dropId = newDropId()
        val userId = "ordering-user"
        seedRemaining(dropId, 10)
        repository.enqueueOrFastAdmit(dropId, userId, 3, TTL_SECONDS)
        // 게이트웨이가 주문 요청에서 입장권을 GETDEL로 소진한 직후를 흉내낸다(admitted 추적은
        // 아직 남아있고, outstanding은 CREATED 이벤트를 받은 queue가 나중에 넘겨받는다).
        redisTemplate.delete(RedisKeys.admission(dropId, userId))

        val released = repository.releaseAdmission(dropId, userId, TOMBSTONE_TTL_SECONDS)

        assertThat(released).isZero()
        // 여기서 깎였다면 CREATED 이벤트 처리 시 또 깎여 이중 차감이 된다.
        assertThat(outstandingOf(dropId)).isEqualTo(3)
        assertThat(admittedScoreOf(dropId, userId)).isNotNull()
    }

    @Test
    @DisplayName("두 번 호출해도 outstanding은 한 번만 되돌아간다(멱등)")
    fun releaseAdmission_twice_decrementsOnce() = runBlocking<Unit> {
        val dropId = newDropId()
        val userId = "ready-user"
        seedRemaining(dropId, 10)
        repository.enqueueOrFastAdmit(dropId, userId, 2, TTL_SECONDS)

        assertThat(repository.releaseAdmission(dropId, userId, TOMBSTONE_TTL_SECONDS)).isEqualTo(2)
        assertThat(repository.releaseAdmission(dropId, userId, TOMBSTONE_TTL_SECONDS)).isZero()

        assertThat(outstandingOf(dropId)).isZero()
    }

    @Test
    @DisplayName(
        "이미 소진된 입장권에 GIVE_UP하면 tombstone을 남긴다 - 게이트웨이가 이 사용자의 " +
            "입장권을 나중에 되살리려 해도(다운스트림 5xx) 이 tombstone이 복구를 막아야 한다"
    )
    fun releaseAdmission_alreadyConsumedTicket_leavesTombstoneForRestoreToCheck() = runBlocking<Unit> {
        val dropId = newDropId()
        val userId = "ordering-user"
        seedRemaining(dropId, 10)
        repository.enqueueOrFastAdmit(dropId, userId, 3, TTL_SECONDS)
        // 게이트웨이가 GETDEL로 입장권을 소진한 직후를 흉내낸다(주문 진행 중).
        redisTemplate.delete(RedisKeys.admission(dropId, userId))

        repository.releaseAdmission(dropId, userId, TOMBSTONE_TTL_SECONDS)

        // 이 키의 존재 여부만이 apigateway의 restore-admission.lua가 복구를 막는 근거다 -
        // 값 자체는 의미가 없다("1"). TTL은 별도로 검증하지 않는다(Testcontainers Redis에서
        // 초 단위 TTL을 짧게 기다리는 건 플레이키해서, 존재 여부만으로 계약을 고정한다).
        assertThat(redisTemplate.hasKey(RedisKeys.giveUpTombstone(dropId, userId))).isTrue()
    }

    @Test
    @DisplayName("아직 소진 전인 입장권을 정상 반납할 때는 tombstone을 남기지 않는다")
    fun releaseAdmission_notYetConsumedTicket_leavesNoTombstone() = runBlocking<Unit> {
        val dropId = newDropId()
        val userId = "ready-user"
        seedRemaining(dropId, 10)
        repository.enqueueOrFastAdmit(dropId, userId, 3, TTL_SECONDS)

        repository.releaseAdmission(dropId, userId, TOMBSTONE_TTL_SECONDS)

        // DEL이 성공한 정상 반납 경로라 tombstone이 불필요하다 - 남기면 이후 이 dropId+userId
        // 조합으로 재진입한 사용자에게 아무 영향은 없지만(restore-admission.lua는 admission
        // 키가 있을 때만 호출되는 경로라 무관), 굳이 남길 이유가 없다는 걸 고정해 둔다.
        assertThat(redisTemplate.hasKey(RedisKeys.giveUpTombstone(dropId, userId))).isFalse()
    }

    private fun outstandingOf(dropId: String): Long =
        redisTemplate.opsForValue().get(RedisKeys.outstanding(dropId))?.toLong() ?: 0

    // 반환 타입을 Double?로 명시한다 - 플랫폼 타입(Double!)을 그대로 넘기면 AssertJ의 원시형
    // assertThat(double) 오버로드가 선택돼 null 언박싱으로 NPE가 난다.
    private fun admittedScoreOf(dropId: String, userId: String): Double? =
        redisTemplate.opsForZSet().score(RedisKeys.admitted(dropId), userId)

    // remaining = total - reserved이므로, reserved를 안 건드리는(0 가정) 대부분의 테스트는
    // total만 세팅하면 "자유 재고 remaining개"를 그대로 흉내낼 수 있다.
    private fun seedRemaining(dropId: String, remaining: Long) {
        redisTemplate.opsForValue().set(RedisKeys.total(dropId), remaining.toString())
    }

    private fun newDropId(): String = "drop-" + java.util.UUID.randomUUID()

    companion object {
        private const val TTL_SECONDS = 60L
        private const val MAX_SCAN = 50
        private const val TOMBSTONE_TTL_SECONDS = 60L

        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)

        private lateinit var connectionFactory: LettuceConnectionFactory
        private lateinit var redisTemplate: StringRedisTemplate
        private lateinit var reactiveRedisTemplate: ReactiveStringRedisTemplate
        private lateinit var repository: WaitingQueueRedisRepository

        @BeforeAll
        @JvmStatic
        fun init() {
            connectionFactory = LettuceConnectionFactory(redis.host, redis.getMappedPort(6379))
            connectionFactory.afterPropertiesSet()
            redisTemplate = StringRedisTemplate(connectionFactory)
            redisTemplate.afterPropertiesSet()
            reactiveRedisTemplate = ReactiveStringRedisTemplate(connectionFactory)
            // 트레이싱 비활성(OpenTelemetry=null)이라 enqueue traceparent 캡처는 no-op이다 — 대기열 로직만 검증한다.
            val queueTraceBridge = QueueTraceBridge(reactiveRedisTemplate, null, 600)
            repository = WaitingQueueRedisRepository(reactiveRedisTemplate, queueTraceBridge)
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
