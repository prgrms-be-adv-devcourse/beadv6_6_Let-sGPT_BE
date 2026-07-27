package com.openat.queue.application.service

import com.openat.queue.application.dto.QueueStatusInfo
import com.openat.queue.application.usecase.GetQueueStatusUseCase
import com.openat.queue.domain.model.QueueStatus
import com.openat.queue.domain.repository.WaitingQueueRepository
import com.openat.queue.infrastructure.config.QueueProperties
import com.openat.queue.infrastructure.persistence.RedisKeys
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Pub/Sub tick과 keepalive tick이 겹쳤을 때 [QueueStreamService]의 재조회(fetchStatus)가
 * 동시에 실행되지 않고 항상 하나씩 직렬로 실행되는지 검증한다(코드 리뷰 지적, 클래스 문서
 * 버그 이력 5번). 병렬로 실행되면 나중에 시작한(트리거는 늦었지만 더 빨리 끝난) 재조회
 * 결과가 먼저 시작한 재조회보다 먼저 클라이언트에 전달돼, 상태가 시간 역순으로 보일 수 있다.
 *
 * Redis Pub/Sub 발행이 필요해 Testcontainers 실제 Redis로 검증한다(WaitingQueueRedisRepositoryTest
 * 와 동일 관례). 실제 시간(runBlocking, delay)을 쓴다 - Redis I/O와 가상 시간을 섞으면
 * 신뢰할 수 없다.
 */
@Testcontainers
@DisplayName("QueueStreamService - Pub/Sub·keepalive 동시 재조회 순서 보장")
class QueueStreamServiceOrderingTest {

    @Test
    @DisplayName("Pub/Sub 신호가 keepalive 재조회 도중 도착해도 fetchStatus 실행 구간이 절대 겹치지 않는다")
    fun stream_pubSubDuringKeepaliveFetch_neverOverlaps() = runBlocking {
        val dropId = "drop-ordering-1"
        val userId = "user-1"
        // (시작, 종료) 나노초 - 트리거된(초기 1회 제외) 재조회만 기록한다.
        val callWindows = CopyOnWriteArrayList<Pair<Long, Long>>()
        val callCount = AtomicInteger(0)
        // 고정 딜레이로 "이쯤이면 keepalive가 재조회를 시작했겠지"라고 추측하지 않는다(Docker/
        // 코루틴 디스패치 오버헤드로 실제 시작 시점이 흔들려 타이밍이 어긋나기 쉽다) - 대신
        // 트리거된 첫 재조회가 "시작하는 바로 그 순간"을 신호로 받아, 그 재조회가 delay(200)
        // 안에서 확실히 진행 중일 때 Pub/Sub 신호를 보낸다.
        val firstTriggeredCallStarted = CompletableDeferred<Unit>()

        val fakeUseCase = GetQueueStatusUseCase { _, _ ->
            val n = callCount.getAndIncrement()
            if (n == 0) {
                // stream() 진입 시 즉시 1회 - 트리거된 호출이 아니므로 측정 대상에서 제외.
                QueueStatusInfo(
                    QueueStatus.WAITING, rank = 0, totalWaiting = 1, quantity = 1,
                    grantableNow = null, optimisticMax = null, pollIntervalMs = 2000,
                )
            } else {
                val start = System.nanoTime()
                if (n == 1) firstTriggeredCallStarted.complete(Unit)
                delay(200) // keepalive-ms(100)보다 훨씬 길게 - 겹치면 반드시 잡히도록.
                val end = System.nanoTime()
                callWindows.add(start to end)
                // totalWaiting을 매번 다르게 바꿔 "상태 변화"로 인식되게 한다 - 안 바뀌면
                // keepalive 코멘트로 대체돼 클라이언트로는 안 나가지만, fetchStatus 자체는
                // 이미 호출된 뒤라 이 테스트가 재는 "재조회 실행 구간" 측정에는 영향 없다.
                QueueStatusInfo(
                    QueueStatus.WAITING, rank = 0, totalWaiting = 1L + n, quantity = 1,
                    grantableNow = null, optimisticMax = null, pollIntervalMs = 2000,
                )
            }
        }

        val service = QueueStreamService(
            getQueueStatusUseCase = fakeUseCase,
            waitingQueueRepository = mock<WaitingQueueRepository>(),
            reactiveRedisTemplate = reactiveRedisTemplate,
            queueProperties = QueueProperties(sse = QueueProperties.Sse(keepaliveMs = 100)),
        )

        val collectJob = launch { service.stream(dropId, userId).collect { } }

        // 첫 keepalive tick이 fetchStatus를 호출해 delay(200) 안에서 진행 중임이 확실한
        // 시점에 Pub/Sub 신호를 보낸다 - 고쳐지지 않았다면 이 신호가 즉시 또 다른
        // fetchStatus를 동시에 실행시킨다.
        firstTriggeredCallStarted.await()
        reactiveRedisTemplate.convertAndSend(RedisKeys.eventsChannel(dropId), "changed").awaitFirstOrNull()

        delay(900) // 트리거된 호출 몇 번이 끝날 시간을 준다.
        collectJob.cancelAndJoin()

        assertThat(callWindows.size)
            .withFailMessage("keepalive/Pub/Sub로 트리거된 재조회가 최소 2번은 있어야 의미 있는 검증이다")
            .isGreaterThanOrEqualTo(2)

        val sorted = callWindows.sortedBy { it.first }
        for (i in 0 until sorted.size - 1) {
            assertThat(sorted[i].second)
                .withFailMessage(
                    "재조회 %d가 끝나기 전에(%d) 재조회 %d가 시작됨(%d) - fetchStatus가 동시에 실행됐다",
                    i, sorted[i].second, i + 1, sorted[i + 1].first,
                )
                .isLessThanOrEqualTo(sorted[i + 1].first)
        }
    }

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)

        private lateinit var connectionFactory: LettuceConnectionFactory
        lateinit var reactiveRedisTemplate: ReactiveStringRedisTemplate

        @BeforeAll
        @JvmStatic
        fun init() {
            connectionFactory = LettuceConnectionFactory(redis.host, redis.getMappedPort(6379))
            connectionFactory.afterPropertiesSet()
            reactiveRedisTemplate = ReactiveStringRedisTemplate(connectionFactory)
        }

        @AfterAll
        @JvmStatic
        fun cleanup() {
            connectionFactory.destroy()
        }
    }
}
