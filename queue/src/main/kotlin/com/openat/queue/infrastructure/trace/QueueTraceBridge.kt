package com.openat.queue.infrastructure.trace

import com.openat.queue.infrastructure.persistence.RedisKeys
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 대기열 enqueue와 admit은 서로 다른 실행 문맥/스케줄러 tick에서 일어나고, Redis에는 트레이스
 * 문맥을 실어 나를 수단이 없어 두 흐름이 단절된다. 이 브릿지는 enqueue 시점의 원 요청 traceparent를
 * Redis 해시에 담아 두었다가, admit 시점에 그 값을 읽어 "queue.admit" 스팬에 원 enqueue 트레이스로의
 * 링크를 건다. 대기 시간이 수 분에 달할 수 있어 두 트레이스를 하나로 합치지 않고(합치면 트레이스가
 * 수 분짜리가 된다) 스팬 링크로 상호 탐색만 가능하게 한다.
 *
 * traceparent 보관은 입장 판정 원자성과 무관한 부가 데이터라 Lua 밖에서 별도 Redis 왕복으로 다룬다.
 * admit 시 필드를 삭제하고 해시 자체에 TTL을 걸어 유령 데이터가 쌓이지 않게 한다(이중 안전망).
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

    /** enqueue 시점의 현재 traceparent를 캡처해 Redis 해시에 저장한다. 문맥이 없으면 아무 것도 안 한다. */
    suspend fun captureEnqueue(dropId: String, userId: String) {
        val traceParent = currentTraceParent() ?: return
        val key = RedisKeys.enqueueTrace(dropId)
        redisTemplate.opsForHash<String, String>().put(key, userId, traceParent).awaitSingleOrNull()
        redisTemplate.expire(key, Duration.ofSeconds(enqueueTtlSeconds)).awaitSingleOrNull()
    }

    /**
     * admit 시점에 저장된 traceparent를 읽어(있으면 삭제) "queue.admit" 스팬에 원 enqueue 트레이스로의
     * 링크를 건 채 스팬을 열고 즉시 닫는다. 저장된 값이 없거나 tracer가 없으면 조용히 지나간다.
     */
    suspend fun linkAdmit(dropId: String, userId: String) {
        val key = RedisKeys.enqueueTrace(dropId)
        val traceParent = redisTemplate.opsForHash<String, String>().get(key, userId).awaitSingleOrNull() ?: return
        redisTemplate.opsForHash<String, String>().remove(key, userId).awaitSingleOrNull()

        val activeTracer = tracer ?: return
        val linkedContext = PROPAGATOR.extract(Context.root(), mapOf(TRACEPARENT to traceParent), GETTER)
        val linkedSpanContext = Span.fromContext(linkedContext).spanContext
        if (!linkedSpanContext.isValid) {
            return
        }
        activeTracer.spanBuilder("queue.admit")
            .addLink(linkedSpanContext)
            .setAttribute("queue.drop_id", dropId)
            .setAttribute("queue.user_id", userId)
            .startSpan()
            .end()
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
