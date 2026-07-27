package com.openat.queue.application.service

import com.openat.queue.application.dto.QueueStatusInfo
import com.openat.queue.application.usecase.GetQueueStatusUseCase
import com.openat.queue.domain.model.QueueStatus
import com.openat.queue.domain.repository.WaitingQueueRepository
import com.openat.queue.infrastructure.config.QueueProperties
import com.openat.queue.infrastructure.persistence.RedisKeys
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component

/**
 * WebFlux+SSE 전환분: `GET /status`(폴링, 유지됨)의 대체 경로. [GetQueueStatusUseCase]를 그대로
 * 재사용하되, "누가 요청했으니 계산한다"가 아니라 "Redis Pub/Sub으로 변경됐다는 신호가 오면
 * 계산한다"로 트리거만 뒤집는다(queue-events:{dropId} 채널,
 * [com.openat.queue.infrastructure.persistence.QueueEventPublisher]가 유일한 발행자).
 *
 * 스트림 구성 3갈래:
 * 1. 연결 즉시 현재 상태 1회 전송(폴링 첫 응답과 동등한 즉시성 보장)
 * 2. Pub/Sub 신호가 올 때마다 재조회
 * 3. keepalive-ms 주기의 안전망 재조회(신호 유실 대비 + idle 커넥션 방지 겸용)
 *
 * 직전에 보낸 값과 실제로 다를 때만 클라이언트로 내려보낸다(불필요한 푸시 방지) - 이게 폴링
 * 대비 "요청 수 자체가 줄어드는" 근거다. READY/SOLD_OUT/NOT_IN_QUEUE 도달 시 스트림을 닫는다.
 *
 * feature/queue-remaining-sync(코루틴 전환): `Flux<ServerSentEvent<T>>` 대신
 * `Flow<ServerSentEvent<T>>`를 반환한다. Reactor의 `concatWith`/`merge`/`takeUntil`/`doFinally`에
 * 대응하는 코루틴 Flow 연산자를 쓴다:
 * - `concatWith` → `flow { emit(...); emitAll(...) }`
 * - `Flux.merge` → `kotlinx.coroutines.flow.merge`
 * - `takeUntil(포함)` → `transformWhile`(값을 emit한 뒤 계속할지 여부를 반환 - "종결 이벤트까지
 *   포함해서 내보내고 멈춘다"를 정확히 표현한다. Flow의 `takeWhile`은 그 값 자체를 emit하지
 *   않고 멈춰서 요구사항과 안 맞는다)
 * - `doFinally` → `onCompletion`(정상 완료/에러/취소 전부에서 호출됨 - `cause`로 구분)
 *
 * 이탈 감지는 여전히 "폴링이 끊겼다"는 추정(heartbeat-ttl 주기 스캔)에만 맡기지 않고, SSE
 * 커넥션이 끊기는 순간을 직접 감지해 그 자리에서 즉시 대기열 자리를 반납한다 -
 * `onCompletion`의 `cause`가 [CancellationException]이면 클라이언트가 브라우저를 닫거나
 * 네트워크가 끊겨 WebFlux가 구독을 취소한 것이다. 아직 WAITING/DECISION_REQUIRED 단계에서
 * 끊겼을 때만 반납한다. 회수 자체는 `withContext(NonCancellable)`로 감싼다 - 이미 취소
 * 신호가 온 코루틴 안에서 추가로 suspend 호출(Redis 삭제)을 하려면 그 호출이 취소를 상속받지
 * 않도록 명시해야 실제로 실행된다(코루틴 취소 전파의 기본 규칙 - 취소된 코루틴은 새 suspend
 * 지점에서 곧바로 `CancellationException`을 던지므로, 정리 작업은 이 경계를 넘어야 한다).
 * `ExpiredWaiterSweeper`의 주기 스캔은 안전망으로 그대로 남겨둔다(예: 이 즉시 반납 자체가
 * 실패하는 극단적 상황 대비) - "유일한 수단을 이벤트로 바꾼 것"이 아니라 "이벤트 기반 즉시
 * 반납을 추가한 것".
 */
@Component
class QueueStreamService(
    private val getQueueStatusUseCase: GetQueueStatusUseCase,
    private val waitingQueueRepository: WaitingQueueRepository,
    private val reactiveRedisTemplate: ReactiveStringRedisTemplate,
    private val queueProperties: QueueProperties,
) {
    private val log = LoggerFactory.getLogger(QueueStreamService::class.java)
    private val terminalStatuses = setOf(QueueStatus.READY, QueueStatus.SOLD_OUT, QueueStatus.NOT_IN_QUEUE)
    private val reclaimableStatuses = setOf(QueueStatus.WAITING, QueueStatus.DECISION_REQUIRED)

    fun stream(dropId: String, userId: String): Flow<ServerSentEvent<QueueStatusInfo>> {
        suspend fun fetchStatus(): QueueStatusInfo = getQueueStatusUseCase.status(dropId, userId)

        val onPubSubTick: Flow<QueueStatusInfo> = reactiveRedisTemplate
            .listenTo(ChannelTopic.of(RedisKeys.eventsChannel(dropId)))
            .asFlow()
            .map { fetchStatus() }

        val onKeepalive: Flow<QueueStatusInfo> = keepaliveTicks(queueProperties.sse.keepaliveMs)
            .map { fetchStatus() }

        val statuses: Flow<QueueStatusInfo> = flow {
            emit(fetchStatus())
            emitAll(merge(onPubSubTick, onKeepalive))
        }

        var lastSent: QueueStatusInfo? = null
        var lastObserved: QueueStatusInfo? = null

        return statuses
            .filter { info ->
                val changed = lastSent != info
                if (changed) lastSent = info
                changed
            }
            .onEach { info -> lastObserved = info }
            .map { info -> ServerSentEvent.builder(info).event("status").build() }
            .transformWhile { event ->
                emit(event)
                event.data()?.status !in terminalStatuses
            }
            .onCompletion { cause -> reclaimOnDisconnect(cause, dropId, userId, lastObserved) }
    }

    private fun keepaliveTicks(intervalMs: Long): Flow<Unit> = flow {
        while (true) {
            delay(intervalMs)
            emit(Unit)
        }
    }

    /** 클라이언트 연결이 끊겨(취소) 스트림이 끝났고, 그 순간 마지막으로 안 상태가 아직
     * 대기열 안(WAITING/DECISION_REQUIRED)이었으면 그 자리에서 바로 회수한다. 정상 완료
     * (READY 등 종결 상태 도달)나 서버 쪽 에러로 끝난 경우는 건드리지 않는다. */
    private suspend fun reclaimOnDisconnect(cause: Throwable?, dropId: String, userId: String, last: QueueStatusInfo?) {
        if (cause !is CancellationException) return
        if (last == null || last.status !in reclaimableStatuses) return
        try {
            withContext(NonCancellable) {
                waitingQueueRepository.removeFromQueue(dropId, userId)
            }
            log.info("[queue-stream-reclaim] dropId={} userId={} 커넥션 종료 감지 - 대기열 자리 즉시 회수", dropId, userId)
        } catch (e: Exception) {
            log.warn("[queue-stream-reclaim] dropId={} userId={} 즉시 회수 실패: {}", dropId, userId, e.toString())
        }
    }
}
