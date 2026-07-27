package com.openat.queue.presentation.controller

import com.openat.common.auth.UserContext
import com.openat.queue.application.dto.QueueStatusInfo
import com.openat.queue.application.service.QueueStreamService
import com.openat.queue.application.usecase.DecideQueueUseCase
import com.openat.queue.application.usecase.EnterQueueUseCase
import com.openat.queue.application.usecase.GetQueueStatusUseCase
import com.openat.queue.domain.model.QueueStatus
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.codec.ServerSentEvent

/**
 * data 없는 keepalive 코멘트 이벤트가 컨트롤러를 그대로 통과하는지 검증한다(코드 리뷰 지적 -
 * `event.data()!!`로 변환하다 상태 변화 없는 keepalive마다 NPE로 스트림이 끊기던 버그의
 * 회귀 테스트, QueueStreamService 클래스 문서 버그 이력 참고).
 */
@DisplayName("QueueController - SSE 스트림 keepalive 코멘트 통과")
class QueueControllerStreamTest {

    @Test
    @DisplayName("data 없는 keepalive 코멘트 이벤트는 예외 없이 코멘트 그대로 전달되고 스트림이 계속된다")
    fun statusStream_commentOnlyKeepaliveEvent_passesThroughWithoutException() = runBlocking {
        val dropId = "drop-1"
        val userContext = UserContext("user-1", setOf("USER"))
        val statusEvent = ServerSentEvent.builder(
            QueueStatusInfo(
                QueueStatus.WAITING, rank = 0, totalWaiting = 1, quantity = 1,
                grantableNow = null, optimisticMax = null, pollIntervalMs = 2000,
            ),
        ).event("status").build()
        val keepaliveEvent = ServerSentEvent.builder<QueueStatusInfo>().comment("keepalive").build()

        val queueStreamService = mock<QueueStreamService>()
        whenever(queueStreamService.stream(dropId, userContext.userId())).thenReturn(
            flow {
                emit(statusEvent)
                emit(keepaliveEvent) // data() == null - 예전엔 여기서 NPE.
                emit(statusEvent)
            },
        )

        val controller = QueueController(
            enterQueueUseCase = mock<EnterQueueUseCase>(),
            getQueueStatusUseCase = mock<GetQueueStatusUseCase>(),
            decideQueueUseCase = mock<DecideQueueUseCase>(),
            queueStreamService = queueStreamService,
        )

        val collected = controller.statusStream(dropId, userContext).toList()

        assertThat(collected).hasSize(3)
        assertThat(collected[0].data()).isNotNull
        assertThat(collected[1].data()).isNull()
        assertThat(collected[1].comment()).isEqualTo("keepalive")
        assertThat(collected[2].data()).isNotNull
    }
}
