package com.openat.queue.presentation.controller

import com.openat.common.auth.CurrentUser
import com.openat.common.auth.UserContext
import com.openat.queue.application.service.QueueStreamService
import com.openat.queue.presentation.dto.QueueDecisionRequest
import com.openat.queue.presentation.dto.QueueEntryRequest
import com.openat.queue.presentation.dto.QueueStatusResponse
import com.openat.queue.application.usecase.DecideQueueUseCase
import com.openat.queue.application.usecase.EnterQueueUseCase
import com.openat.queue.application.usecase.GetQueueStatusUseCase
import jakarta.validation.Valid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * presentation 계층: UseCase 인터페이스에만 의존하고 구현체는 모른다(얇은 컨트롤러).
 *
 * 이 엔드포인트들은 게이트웨이의 `AdmissionCheck` 필터를 타지 않는다 - hot-drops 여부와는
 * 무관하고(그런 정적 목록 자체가 없다), 그 필터가 라우트 설정에서 애초에 주문 경로
 * (`/api/v1/orders` 하위 전체)에만 붙어 있어 대기열 경로(`/api/v1/queues` 하위 전체)는
 * 필터 자체를 지나지 않기 때문이다. JWT만으로 접근 가능한 것도 같은 이유(게이트웨이
 * `anyExchange().access(authenticatedAndNotScoped())`가 기본으로 커버 - 별도 permitAll 불필요).
 *
 * feature/queue-remaining-sync(코루틴 전환): `entry`/`status`/`decision`은 `suspend fun`으로
 * 유스케이스를 직접 호출한다(`Mono<QueueStatusInfo>`를 `.map`하는 대신, 값을 그냥 받아서 바로
 * 변환). `Schedulers.boundedElastic()` 브릿지는 여전히 없다 - 요청이 컨트롤러에 들어와서
 * 나갈 때까지 Netty 이벤트루프 스레드 안에서 끝난다(QueueService/WaitingQueueRedisRepository가
 * 논블로킹 `ReactiveStringRedisTemplate`만 쓰기 때문 - suspend는 그 호출을 감싸는 문법일 뿐,
 * 스레드를 새로 만들지 않는다). `status`(폴링)는 회귀테스트 호환을 위해 그대로 남겨뒀고,
 * `status/stream`이 SSE 전용 경로다(`Flow<ServerSentEvent<...>>` 반환 - QueueStreamService 참고).
 */
@RestController
@RequestMapping("/api/v1/queues")
class QueueController(
    private val enterQueueUseCase: EnterQueueUseCase,
    private val getQueueStatusUseCase: GetQueueStatusUseCase,
    private val decideQueueUseCase: DecideQueueUseCase,
    private val queueStreamService: QueueStreamService,
) {

    @PostMapping("/{dropId}/entry")
    suspend fun enter(
        @PathVariable dropId: String,
        @CurrentUser userContext: UserContext,
        @Valid @RequestBody(required = false) request: QueueEntryRequest?,
    ): QueueStatusResponse {
        val info = enterQueueUseCase.enter(dropId, userContext.userId(), (request ?: QueueEntryRequest()).quantity)
        return QueueStatusResponse.from(info)
    }

    /** 기존 폴링 경로. 새로 만들지 않는다 - `burst-test.sh` 등 기존 회귀테스트/도구가 이 경로를
     * 그대로 쓰므로 호환을 위해 유지한다. 신규 클라이언트는 `status/stream`을 쓸 것. */
    @GetMapping("/{dropId}/status")
    suspend fun status(
        @PathVariable dropId: String,
        @CurrentUser userContext: UserContext,
    ): QueueStatusResponse =
        QueueStatusResponse.from(getQueueStatusUseCase.status(dropId, userContext.userId()))

    /** WebFlux+SSE 전환분: 상태가 바뀔 때만 서버가 밀어준다(폴링 없음). READY/SOLD_OUT/
     * NOT_IN_QUEUE에 도달하면 서버가 스트림을 닫는다. */
    @GetMapping("/{dropId}/status/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun statusStream(
        @PathVariable dropId: String,
        @CurrentUser userContext: UserContext,
    ): Flow<ServerSentEvent<QueueStatusResponse>> =
        queueStreamService.stream(dropId, userContext.userId())
            .map { event ->
                // keepalive tick(상태 변화 없음)은 QueueStreamService가 data 없이 comment만
                // 채워 보낸다(NPE 버그 이력 - event.data()!!로 바로 변환하면 매 keepalive마다
                // 스트림이 죽었다) - data가 없으면 comment만 그대로 전달한다.
                val data = event.data()
                if (data != null) {
                    ServerSentEvent.builder(QueueStatusResponse.from(data))
                        .event("status")
                        .build()
                } else {
                    ServerSentEvent.builder<QueueStatusResponse>()
                        .comment(event.comment() ?: "keepalive")
                        .build()
                }
            }

    /** `DECISION_REQUIRED` 상태에 대한 응답(WAIT/PARTIAL/GIVE_UP). 결과 상태를 즉시 반환해
     * 다음 갱신을 기다리지 않고도 바로 반영된 화면을 보여줄 수 있게 한다. */
    @PostMapping("/{dropId}/decision")
    suspend fun decide(
        @PathVariable dropId: String,
        @CurrentUser userContext: UserContext,
        @Valid @RequestBody request: QueueDecisionRequest,
    ): QueueStatusResponse {
        val info = decideQueueUseCase.decide(dropId, userContext.userId(), requireNotNull(request.choice))
        return QueueStatusResponse.from(info)
    }
}
