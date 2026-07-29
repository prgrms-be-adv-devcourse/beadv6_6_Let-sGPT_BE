package com.openat.queue.infrastructure.trace

import com.openat.queue.infrastructure.persistence.RedisKeys
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

/**
 * 대기열 enqueue와 admit은 서로 다른 실행 문맥/스케줄러 tick에서 일어나고, Redis에는 트레이스
 * 문맥을 실어 나를 수단이 없어 두 흐름이 단절된다. 이 브릿지는 enqueue 시점의 원 요청 traceparent를
 * Redis 해시에 담아 두었다가, admit 시점에 그 값을 읽어 "queue.admit" 스팬에 원 enqueue 트레이스로의
 * 링크를 건다. 대기 시간이 수 분에 달할 수 있어 두 트레이스를 하나로 합치지 않고(합치면 트레이스가
 * 수 분짜리가 된다) 스팬 링크로 상호 탐색만 가능하게 한다.
 *
 * 저장은 반드시 enqueue Lua "이전"에 완료돼야 한다 — 재고가 있으면 enqueue-or-admit Lua가 그 자리에서
 * 즉시 입장시키거나 admit 스케줄러가 곧바로 다음 tick에 입장시키므로, Lua 이후에 저장하면 admit 측
 * 조회가 저장을 앞질러 링크가 유실된다. 즉시 입장돼 링크되지 않는 고아 항목이나 저장 실패분은 해시
 * TTL이 청소하므로 무해하다. traceparent 보관은 입장 판정 원자성과 무관한 부가 데이터라 admit Lua는
 * 건드리지 않고 별도 키에서 다룬다.
 *
 * OpenTelemetry 빈이 없는 환경(트레이싱 비활성/테스트)에서도 기동이 깨지지 않도록 nullable로 받아
 * (Kotlin nullable 생성자 파라미터 = 선택적 주입) 링크 생성은 조용히 건너뛴다.
 */
@Component
class QueueTraceBridge(
    private val redisTemplate: ReactiveStringRedisTemplate,
    openTelemetry: OpenTelemetry?,
    @Value("\${queue.trace.enqueue-ttl-seconds:600}") private val enqueueTtlSeconds: Long,
) {

    private val tracer = openTelemetry?.getTracer(INSTRUMENTATION_SCOPE)

    // HSET과 TTL 설정을 한 번의 왕복으로 원자 처리한다. TTL은 해시가 처음 생성될 때만 걸어(이미 TTL이
    // 있으면 슬라이딩으로 밀지 않는다) enqueue 핫패스의 왕복을 하나로 줄인다.
    private val captureScript: RedisScript<Long> = RedisScript.of(
        """
        redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
        if redis.call('TTL', KEYS[1]) < 0 then
            redis.call('EXPIRE', KEYS[1], ARGV[3])
        end
        return 1
        """.trimIndent(),
        Long::class.java,
    )

    /**
     * enqueue 시점의 현재 traceparent를 캡처해 Redis 해시에 저장한다. 문맥이 없으면 아무 것도 안 한다.
     * 반드시 enqueue Lua 호출 전에 await로 완료해, admit 측 조회가 저장을 앞지르지 않게 한다.
     */
    suspend fun captureEnqueue(dropId: String, userId: String) {
        val traceParent = currentTraceParent() ?: return
        redisTemplate.execute(
            captureScript,
            listOf(RedisKeys.enqueueTrace(dropId)),
            listOf(userId, traceParent, enqueueTtlSeconds.toString()),
        ).awaitFirstOrNull()
    }

    /**
     * 이번 tick에 입장 처리된 사용자들의 저장된 traceparent를 HMGET 1회로 읽고 HDEL 1회로 지운 뒤,
     * 값이 있는 사용자마다 원 enqueue 트레이스로 링크를 건 "queue.admit" 스팬을 열고 즉시 닫는다.
     * 사용자 수 N에 비례하던 왕복(GET/HDEL 2N)을 상수 2회로 줄인다. 저장된 값이 없거나 tracer가
     * 없으면 조용히 지나간다.
     */
    suspend fun linkAdmitBatch(dropId: String, userIds: List<String>) {
        if (userIds.isEmpty()) {
            return
        }
        val key = RedisKeys.enqueueTrace(dropId)
        val values: List<String?> =
            redisTemplate.opsForHash<String, String>().multiGet(key, userIds).awaitSingleOrNull() ?: return
        val present = userIds.zip(values).mapNotNull { (userId, traceParent) ->
            traceParent?.let { userId to it }
        }
        if (present.isEmpty()) {
            return
        }
        redisTemplate.opsForHash<String, String>()
            .remove(key, *present.map { it.first }.toTypedArray())
            .awaitSingleOrNull()

        val activeTracer = tracer ?: return
        for ((_, traceParent) in present) {
            val linkedContext = PROPAGATOR.extract(Context.root(), mapOf(TRACEPARENT to traceParent), GETTER)
            val linkedSpanContext = Span.fromContext(linkedContext).spanContext
            if (!linkedSpanContext.isValid) {
                continue
            }
            // dropId만 속성으로 남긴다 — userId는 고카디널리티 개인식별자라 스팬 속성으로 싣지 않는다.
            activeTracer.spanBuilder("queue.admit")
                .addLink(linkedSpanContext)
                .setAttribute("queue.drop_id", dropId)
                .startSpan()
                .end()
        }
    }

    private fun currentTraceParent(): String? {
        val carrier = HashMap<String, String>()
        PROPAGATOR.inject(Context.current(), carrier, SETTER)
        return carrier[TRACEPARENT]
    }

    companion object {
        private const val INSTRUMENTATION_SCOPE = "com.openat.queue.admission"
        private const val TRACEPARENT = "traceparent"
        private val PROPAGATOR = W3CTraceContextPropagator.getInstance()

        private val SETTER = TextMapSetter<MutableMap<String, String>> { carrier, key, value ->
            if (carrier != null && value != null) {
                carrier[key] = value
            }
        }

        private val GETTER = object : TextMapGetter<Map<String, String>> {
            override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys
            override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key)
        }
    }
}
