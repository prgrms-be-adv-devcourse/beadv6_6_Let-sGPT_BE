package com.openat.queue.presentation.controller

import com.openat.common.auth.CurrentUser
import com.openat.common.auth.UserContext
import com.openat.queue.presentation.dto.QueueDecisionRequest
import com.openat.queue.presentation.dto.QueueEntryRequest
import com.openat.queue.presentation.dto.QueueStatusResponse
import com.openat.queue.application.usecase.DecideQueueUseCase
import com.openat.queue.application.usecase.EnterQueueUseCase
import com.openat.queue.application.usecase.GetQueueStatusUseCase
import jakarta.validation.Valid
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
 */
@RestController
@RequestMapping("/api/v1/queues")
class QueueController(
    private val enterQueueUseCase: EnterQueueUseCase,
    private val getQueueStatusUseCase: GetQueueStatusUseCase,
    private val decideQueueUseCase: DecideQueueUseCase,
) {

    @PostMapping("/{dropId}/entry")
    fun enter(
        @PathVariable dropId: String,
        @CurrentUser userContext: UserContext,
        @Valid @RequestBody(required = false) request: QueueEntryRequest?,
    ): QueueStatusResponse =
        QueueStatusResponse.from(
            enterQueueUseCase.enter(dropId, userContext.userId(), (request ?: QueueEntryRequest()).quantity),
        )

    @GetMapping("/{dropId}/status")
    fun status(
        @PathVariable dropId: String,
        @CurrentUser userContext: UserContext,
    ): QueueStatusResponse =
        QueueStatusResponse.from(getQueueStatusUseCase.status(dropId, userContext.userId()))

    /** `DECISION_REQUIRED` 상태에 대한 응답(WAIT/PARTIAL/GIVE_UP). 결과 상태를 즉시 반환해
     * 다음 폴링을 기다리지 않고도 바로 반영된 화면을 보여줄 수 있게 한다. */
    @PostMapping("/{dropId}/decision")
    fun decide(
        @PathVariable dropId: String,
        @CurrentUser userContext: UserContext,
        @Valid @RequestBody request: QueueDecisionRequest,
    ): QueueStatusResponse =
        QueueStatusResponse.from(
            decideQueueUseCase.decide(dropId, userContext.userId(), requireNotNull(request.choice)),
        )
}
