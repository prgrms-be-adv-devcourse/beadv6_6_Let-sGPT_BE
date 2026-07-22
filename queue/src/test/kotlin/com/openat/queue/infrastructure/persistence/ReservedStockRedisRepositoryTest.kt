package com.openat.queue.infrastructure.persistence

import com.openat.queue.domain.model.StockAdjustmentReason
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import java.util.UUID

/**
 * `ReservedStockRedisRepository`(선점 누적 수량)를 실제 Redis로 검증한다.
 *
 * CREATED는 CANCELLED와 다른 경로([applyCreatedReservationAndReleaseOutstanding])로
 * 처리한다 - reserved 증가와 outstanding 감소를 원자적으로 같이 해야 하기 때문(라이브 데모의
 * "이중 공백" 레이스 수정, apply-created-reservation.lua 헤더 주석 참고). CANCELLED는 여전히
 * `apply-stock-adjustment.lua`를 `ConfirmedSalesRedisRepository`와 공유해서 쓴다(outstanding은
 * CREATED 시점에 이미 정리됐으므로 손댈 필요가 없다).
 */
@Testcontainers
@DisplayName("선점(reserved) Redis 저장소 - CREATED/CANCELLED 가감 + outstanding 인계 + 멱등 처리")
class ReservedStockRedisRepositoryTest {

    @Test
    @DisplayName("이벤트가 없으면 선점 수량은 0이다")
    fun reservedOf_noEvents_returnsZero() {
        assertThat(repository.reservedOf(newDropId())).isEqualTo(0)
    }

    @Test
    @DisplayName("CREATED는 reserved를 늘리고 outstanding을 같은 양만큼 원자적으로 줄인다(핸드오프)")
    fun applyCreatedReservationAndReleaseOutstanding_incrementsReservedAndDecrementsOutstanding() {
        val dropId = newDropId()
        seedOutstanding(dropId, 5)

        repository.applyCreatedReservationAndReleaseOutstanding(dropId, newEventId(), 3)

        assertThat(repository.reservedOf(dropId)).isEqualTo(3)
        assertThat(outstandingOf(dropId)).isEqualTo(2)
    }

    @Test
    @DisplayName("같은 eventId가 재전달돼도(Kafka at-least-once) reserved/outstanding 둘 다 중복 반영하지 않는다")
    fun applyCreatedReservationAndReleaseOutstanding_duplicateEventId_appliedOnce() {
        val dropId = newDropId()
        seedOutstanding(dropId, 5)
        val eventId = newEventId()

        repository.applyCreatedReservationAndReleaseOutstanding(dropId, eventId, 3)
        repository.applyCreatedReservationAndReleaseOutstanding(dropId, eventId, 3) // 재전달 시뮬레이션

        assertThat(repository.reservedOf(dropId)).isEqualTo(3)
        assertThat(outstandingOf(dropId)).isEqualTo(2)
    }

    @Test
    @DisplayName("CANCELLED는 reserved만 줄이고 outstanding은 건드리지 않는다")
    fun applyReservationAdjustment_cancelledSubtractsReservedOnly() {
        val dropId = newDropId()
        seedOutstanding(dropId, 5)
        repository.applyCreatedReservationAndReleaseOutstanding(dropId, newEventId(), 5) // reserved=5, outstanding=0

        repository.applyReservationAdjustment(dropId, newEventId(), 2, StockAdjustmentReason.CANCELLED)

        assertThat(repository.reservedOf(dropId)).isEqualTo(3)
        assertThat(outstandingOf(dropId)).isEqualTo(0) // CREATED 시점에 이미 정리됨 - CANCELLED가 다시 안 건드림
    }

    @Test
    @DisplayName("CREATED를 applyReservationAdjustment로 잘못 호출하면 라우팅 버그로 즉시 실패한다")
    fun applyReservationAdjustment_createdReason_failsFast() {
        assertThatThrownBy {
            repository.applyReservationAdjustment(newDropId(), newEventId(), 1, StockAdjustmentReason.CREATED)
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    @DisplayName("서로 다른 dropId는 완전히 독립적으로 집계된다")
    fun applyCreatedReservationAndReleaseOutstanding_isolatedPerDropId() {
        val dropA = newDropId()
        val dropB = newDropId()
        seedOutstanding(dropA, 10)

        repository.applyCreatedReservationAndReleaseOutstanding(dropA, newEventId(), 10)

        assertThat(repository.reservedOf(dropA)).isEqualTo(10)
        assertThat(repository.reservedOf(dropB)).isEqualTo(0)
    }

    private fun newDropId(): String = "drop-" + UUID.randomUUID()
    private fun newEventId(): String = UUID.randomUUID().toString()

    private fun seedOutstanding(dropId: String, value: Long) {
        redisTemplate.opsForValue().set(RedisKeys.outstanding(dropId), value.toString())
    }

    private fun outstandingOf(dropId: String): Long =
        redisTemplate.opsForValue().get(RedisKeys.outstanding(dropId))?.toLongOrNull() ?: 0

    companion object {
        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine").withExposedPorts(6379)

        private lateinit var connectionFactory: LettuceConnectionFactory
        private lateinit var redisTemplate: StringRedisTemplate
        private lateinit var repository: ReservedStockRedisRepository

        @BeforeAll
        @JvmStatic
        fun init() {
            connectionFactory = LettuceConnectionFactory(redis.host, redis.getMappedPort(6379))
            connectionFactory.afterPropertiesSet()
            redisTemplate = StringRedisTemplate(connectionFactory)
            redisTemplate.afterPropertiesSet()
            repository = ReservedStockRedisRepository(redisTemplate)
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
