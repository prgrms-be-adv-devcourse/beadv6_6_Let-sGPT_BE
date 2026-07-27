package com.openat.queue.application.service

import com.openat.queue.application.dto.QueueStatusInfo
import com.openat.queue.application.usecase.GetQueueStatusUseCase
import com.openat.queue.domain.model.QueueStatus
import com.openat.queue.domain.repository.WaitingQueueRepository
import com.openat.queue.infrastructure.config.QueueProperties
import com.openat.queue.infrastructure.persistence.RedisKeys
import jakarta.annotation.PreDestroy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
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
 * 스트림 구성:
 * 1. 연결 즉시 현재 상태 1회 전송(폴링 첫 응답과 동등한 즉시성 보장)
 * 2. Pub/Sub 신호가 올 때마다 재조회
 * 3. keepalive-ms 주기의 안전망 재조회(신호 유실 대비 + heartbeat 갱신 겸용)
 *
 * 재조회 결과가 직전에 보낸 값과 실제로 다를 때만 상태 이벤트로 클라이언트에 내려보낸다
 * (불필요한 푸시 방지 - 폴링 대비 "요청 수 자체가 줄어드는" 근거) - **트리거가 Pub/Sub이든
 * keepalive든 이 규칙은 동일하다**: keepalive tick이 "재조회했더니 마침 값이 바뀌어 있었다"를
 * 발견하면 그 값도 똑같이 상태 이벤트로 나간다(Pub/Sub 신호가 유실됐을 때 keepalive가 그걸
 * 잡아주는 게 "안전망"이라는 이름의 실질적 근거 - 재조회만 하고 결과를 버리면 안전망이 아니다).
 * 값이 안 바뀌었을 때만 "커넥션이 살아있다"는 SSE 코멘트로 대체한다 - 이러면 매 tick마다
 * 뭔가는(상태 이벤트든 코멘트든) 반드시 전송되어 idle 커넥션 타임아웃도 막힌다.
 * READY/SOLD_OUT/NOT_IN_QUEUE 도달 시 스트림을 닫는다.
 *
 * **버그 수정 이력(코드 리뷰 반영, 2026-07)**:
 * 1. 최초 구현은 keepalive 재조회 결과를 "상태 변화" 필터에 그대로 태워서, 상태가 안 바뀌면
 *    재조회는 일어나도(heartbeat는 갱신됨) SSE 바이트 자체는 하나도 안 나갔다 - "idle 커넥션
 *    타임아웃 방지"라는 문서화된 목적이 실제로는 동작하지 않았다.
 * 2. 그 다음 버전(1차 수정)은 keepalive를 별도 흐름으로 완전히 분리하면서, keepalive
 *    재조회의 결과값 자체를 버리고 하트비트 갱신 부수효과로만 썼다 - "바이트를 안 보내는"
 *    문제는 고쳤지만, 그 과정에서 "Pub/Sub 신호 유실 시 keepalive가 놓친 변화를 다시 잡아
 *    준다"는 안전망의 존재 이유 자체를 없애버렸다(재발 버그). 한 번 이상한/오래된 스냅샷을
 *    보내고 나면 다음 진짜 Pub/Sub 신호가 올 때까지 자가 교정이 안 됐다 - "기다린다" 결정
 *    직후 잘못된 상태가 잠깐 보이거나, 다른 대기자의 GIVE_UP이 반영 안 되고 멈추는 증상으로
 *    나타났다. 지금 버전은 트리거 종류와 무관하게 재조회 결과가 바뀌었으면 항상 전달한다.
 * 3. SSE 연결이 끊기면(취소) 바로 대기열에서 제거했는데, "연결 끊김"의 흔한 원인(지하철
 *    터널 진입, 폰 화면 잠금/앱 전환, 다중 탭 중 하나만 닫힘, 브라우저 자동 재연결)은 전부
 *    "곧 다시 붙는" 끊김이지 "진짜로 나감"이 아니다. 이제 같은 dropId+userId의 마지막
 *    연결이 끊긴 뒤 [QueueProperties.Waiting.reconnectGraceMs] 동안 기다렸다가 그 사이 재연결이
 *    없을 때만 실제로 회수한다(재연결이 오면 예약된 회수를 취소). 다중 탭은 연결 수를 세어
 *    구분한다 - 하나가 끊겨도 다른 연결이 남아있으면 아무 것도 하지 않는다.
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
 * 이탈 감지는 여전히 "폴링/SSE 재조회가 끊겼다"는 추정(heartbeat-ttl 주기 스캔, [
 * com.openat.queue.infrastructure.schedule.ExpiredWaiterSweeper])에만 맡기지 않고, 이 클래스가
 * SSE 커넥션이 끊기는 순간을 직접 감지해 유예 시간 뒤 대기열 자리를 반납한다 - 다만 이건
 * "더 빠른 정리"라는 최적화이지 유일한 수단이 아니다. 재연결 유예 로직 자체가 실패하거나
 * (재기동 등) 서버가 죽어서 정리를 못 하는 극단적 상황은 여전히 스위퍼가 최종적으로
 * 정리한다.
 *
 * **연결 수 카운터(`activeConnections`)는 이 인스턴스(파드) 로컬 메모리 상태**다 - 분산
 * 스토어가 아니다. `k8s/base/27-queue.yaml`이 `replicas: 1`인 지금은 문제없지만, 나중에
 * 레플리카를 늘리면 로드밸런서/인그레스에 세션 어피니티가 없는 한 재연결이 다른 파드로
 * 갈 수 있어 이 카운터가 안 맞을 수 있다 - 그래도 최악의 경우 여전히 heartbeat-ttl 스위퍼가
 * 정리하므로 정합성이 깨지지는 않는다(반응 속도만 최적화 없이 원래대로 돌아감).
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

    // 같은 dropId+userId에 대해 지금 열려 있는 SSE 구독 수(다중 탭 구분용)와, 그 수가 0이 된
    // 뒤 예약된 지연 회수 작업(재연결 시 취소 대상)을 추적한다. 인스턴스 로컬 상태 - 클래스
    // 문서 참고.
    private val activeConnections = ConcurrentHashMap<String, AtomicInteger>()
    private val pendingReclaims = ConcurrentHashMap<String, Job>()
    private val reclaimScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @PreDestroy
    fun shutdown() {
        reclaimScope.cancel()
    }

    fun stream(dropId: String, userId: String): Flow<ServerSentEvent<QueueStatusInfo>> {
        suspend fun fetchStatus(): QueueStatusInfo = getQueueStatusUseCase.status(dropId, userId)

        val connectionKey = "$dropId:$userId"

        val onPubSubTick: Flow<QueueStatusInfo> = reactiveRedisTemplate
            .listenTo(ChannelTopic.of(RedisKeys.eventsChannel(dropId)))
            .asFlow()
            .map { fetchStatus() }

        // keepalive tick도 "재조회"라는 점은 Pub/Sub tick과 동일하다 - 안전망(신호 유실 대비)
        // 역할을 하려면 이 재조회 결과도 반드시 아래 changed-필터를 거쳐 실제 상태 이벤트로
        // 나갈 수 있어야 한다(재조회만 하고 결과를 버리면 안전망이 이름만 안전망이다 - 버그
        // 이력, 아래 참고).
        val onKeepaliveTick: Flow<QueueStatusInfo> = keepaliveTicks(queueProperties.sse.keepaliveMs)
            .map { fetchStatus() }

        val statuses: Flow<QueueStatusInfo> = flow {
            emit(fetchStatus())
            emitAll(merge(onPubSubTick, onKeepaliveTick))
        }

        var lastSent: QueueStatusInfo? = null
        var lastObserved: QueueStatusInfo? = null

        // 재조회 결과가 직전과 다르면(어느 트리거로 재조회했든) 그 상태를 그대로 전달하고,
        // 같으면 "그냥 살아있다"는 keepalive 코멘트로 대체한다 - 상태 변화는 트리거 종류와
        // 무관하게 항상 전달되고(안전망이 실제로 동작), 동시에 매 tick마다 뭔가는 반드시
        // 전송되어(코멘트라도) idle 타임아웃도 막힌다.
        //
        // **버그 수정(2026-07, 재발)**: 이전 버전은 keepalive tick의 재조회 결과를 버리고
        // 하트비트 갱신 부수효과로만 썼다 - "keepalive가 바이트를 안 보내던 버그"는 고쳤지만
        // 그 과정에서 "Pub/Sub 신호 유실 시 keepalive가 놓친 변화를 다시 잡아준다"는 원래
        // 목적을 없애버렸다. 한 번 이상한 스냅샷을 보내고 나면 다음 진짜 변화(Pub/Sub 신호)가
        // 올 때까지 자가 교정이 안 됐다 - "기다린다" 결정 직후 잘못된 상태가 보이거나, 다른
        // 대기자의 GIVE_UP이 반영 안 되고 멈추는 증상으로 나타났다.
        val events: Flow<ServerSentEvent<QueueStatusInfo>> = statuses.map { info ->
            lastObserved = info
            val changed = lastSent != info
            if (changed) {
                lastSent = info
                ServerSentEvent.builder(info).event("status").build()
            } else {
                ServerSentEvent.builder<QueueStatusInfo>().comment("keepalive").build()
            }
        }

        return events
            .onStart { onConnectionOpened(connectionKey) }
            .transformWhile { event ->
                emit(event)
                // 코멘트 이벤트(data 없음)는 계속 진행, 상태 이벤트는 종결 상태가 아닐 때만 계속.
                event.data()?.let { it.status !in terminalStatuses } ?: true
            }
            .onCompletion { cause -> onConnectionClosed(cause, connectionKey, dropId, userId, lastObserved) }
    }

    private fun keepaliveTicks(intervalMs: Long): Flow<Unit> = flow {
        while (true) {
            delay(intervalMs)
            emit(Unit)
        }
    }

    /** 새 SSE 구독이 시작될 때 연결 수를 증가시키고, 그 dropId+userId에 예약돼 있던 지연
     * 회수가 있으면 취소한다(재연결 성공 - 자리를 빼앗기지 않는다). */
    private fun onConnectionOpened(key: String) {
        activeConnections.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
        pendingReclaims.remove(key)?.cancel()
    }

    /** SSE 연결이 끝났을 때(정상/에러/취소 전부) 연결 수를 줄인다. 같은 dropId+userId의 다른
     * 연결이 아직 남아있으면(다중 탭) 아무 것도 하지 않는다 - 마지막 연결이 취소로 끝났을
     * 때만, 그것도 즉시가 아니라 유예 시간 뒤에 회수를 예약한다. */
    private fun onConnectionClosed(
        cause: Throwable?,
        key: String,
        dropId: String,
        userId: String,
        last: QueueStatusInfo?,
    ) {
        val remaining = activeConnections[key]?.decrementAndGet() ?: 0
        if (remaining > 0) return // 다른 탭/연결이 아직 열려 있음.
        activeConnections.remove(key)

        if (cause !is CancellationException) return
        if (last == null || last.status !in reclaimableStatuses) return

        val graceMs = queueProperties.waiting.reconnectGraceMs
        val job = reclaimScope.launch {
            delay(graceMs)
            // Job 생성과 pendingReclaims 등록 사이의 좁은 틈에 재연결이 끼어들면
            // onConnectionOpened의 취소가 이 Job을 못 찾을 수 있다(리뷰 지적) - 그래서 실제로
            // 회수하기 직전에 "그 사이 연결이 이미 돌아왔는지"를 한 번 더 확인한다. 이러면
            // 등록 타이밍과 무관하게 재연결된 정상 사용자를 잘못 회수할 수 없다.
            if ((activeConnections[key]?.get() ?: 0) > 0) return@launch
            try {
                withContext(NonCancellable) {
                    waitingQueueRepository.removeFromQueue(dropId, userId)
                }
                log.info(
                    "[queue-stream-reclaim] dropId={} userId={} 재연결 유예({}ms) 경과 - 대기열 자리 회수",
                    dropId, userId, graceMs,
                )
            } catch (e: Exception) {
                log.warn("[queue-stream-reclaim] dropId={} userId={} 회수 실패: {}", dropId, userId, e.toString())
            } finally {
                pendingReclaims.remove(key)
            }
        }
        pendingReclaims[key] = job
    }
}
