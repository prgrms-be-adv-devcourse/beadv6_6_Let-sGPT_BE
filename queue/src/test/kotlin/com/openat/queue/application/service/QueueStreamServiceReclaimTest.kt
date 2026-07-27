package com.openat.queue.application.service

import com.openat.queue.domain.repository.WaitingQueueRepository
import com.openat.queue.infrastructure.config.QueueProperties
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.springframework.data.redis.core.ReactiveStringRedisTemplate

/**
 * SSE 연결이 끊긴 뒤 유예 시간이 지나 실제로 대기열 자리를 회수할지 판단하는
 * [QueueStreamService.attemptReclaim]과, 재연결을 알리는 [QueueStreamService.onConnectionOpened]
 * 사이의 TOCTOU 레이스 수정을 검증한다(코드 리뷰 지적, 클래스 문서 버그 이력 4번).
 *
 * `stream()`(Redis Pub/Sub 구독 필요)은 거치지 않는다 - 이 두 메서드는 인스턴스 로컬 메모리
 * 상태(activeConnections)와 [WaitingQueueRepository.removeFromQueue]만 다루므로 Docker 없이
 * Mockito로 검증 가능하다(QueueServiceDecisionTest와 동일 관례).
 */
@DisplayName("QueueStreamService - 재연결/회수 레이스")
class QueueStreamServiceReclaimTest {

    private val dropId = "drop-1"
    private val userId = "user-1"
    private val key = "$dropId:$userId"

    private fun newService(repository: WaitingQueueRepository): QueueStreamService =
        QueueStreamService(
            getQueueStatusUseCase = mock(),
            waitingQueueRepository = repository,
            reactiveRedisTemplate = mock<ReactiveStringRedisTemplate>(),
            queueProperties = QueueProperties(),
        )

    @Test
    @DisplayName("attemptReclaim 실행 전에 onConnectionOpened가 먼저 끝났으면 removeFromQueue를 호출하지 않는다")
    fun attemptReclaim_afterReconnectAlreadyHappened_skipsRemoval() = runBlocking {
        val repository = mock<WaitingQueueRepository>()
        val service = newService(repository)

        // 재연결이 회수 판단보다 먼저 "완전히" 끝난 경우 - 재연결된 사용자는 절대 회수되면 안 된다.
        service.onConnectionOpened(key)
        service.attemptReclaim(key, dropId, userId, graceMs = 5000)

        verify(repository, never()).removeFromQueue(any(), any())
    }

    @Test
    @DisplayName("재연결이 전혀 없었으면 attemptReclaim이 정상적으로 회수한다")
    fun attemptReclaim_noReconnect_removesUser() = runBlocking {
        val repository = mock<WaitingQueueRepository>()
        val service = newService(repository)

        service.attemptReclaim(key, dropId, userId, graceMs = 5000)

        verify(repository).removeFromQueue(dropId, userId)
    }

    @Test
    @DisplayName("여러 dropId+userId 키가 섞여도 서로의 Mutex/연결 상태에 영향을 주지 않는다")
    fun attemptReclaim_independentKeys_doNotInterfere() = runBlocking {
        val repository = mock<WaitingQueueRepository>()
        val service = newService(repository)
        val otherKey = "drop-1:user-2"

        // user-1만 재연결됨, user-2는 재연결 없음.
        service.onConnectionOpened(key)
        service.attemptReclaim(key, dropId, userId, graceMs = 5000)
        service.attemptReclaim(otherKey, dropId, "user-2", graceMs = 5000)

        verify(repository, never()).removeFromQueue(dropId, userId)
        verify(repository).removeFromQueue(dropId, "user-2")
    }

    @Test
    @DisplayName("동시(concurrent) 재연결과 회수 시도를 여러 번 반복해도 상태가 항상 일관된다")
    fun attemptReclaim_concurrentWithReconnect_neverInconsistent() = runBlocking {
        // 실제 스레드 경합으로 반복 검증 - Mutex가 onConnectionOpened와 attemptReclaim을
        // 완전히 직렬화하므로, 매 시행마다 "재연결이 이겼으면 회수 안 됨" 또는 "회수가
        // 이겼으면 removeFromQueue가 정확히 한 번 호출됨" 둘 중 하나만 관찰돼야 한다 -
        // 둘 다 아닌 상태(예: 회수됐는데 그 뒤로도 재연결 카운터가 남아있다고 착각하는 등)는
        // 없어야 한다.
        repeat(200) { trial ->
            val repository = mock<WaitingQueueRepository>()
            val service = newService(repository)
            val trialKey = "drop-1:user-$trial"

            coroutineScope {
                launch { service.onConnectionOpened(trialKey) }
                launch { service.attemptReclaim(trialKey, dropId, "user-$trial", graceMs = 0) }
            }

            // 둘 중 어느 쪽이 이겼든(타이밍에 따라 달라질 수 있음), removeFromQueue는
            // 최대 1번만 호출돼야 하고, 호출됐다면 정확히 그 사용자여야 한다.
            val invocations = org.mockito.Mockito.mockingDetails(repository).invocations
                .count { it.method.name == "removeFromQueue" }
            assertThat(invocations).isLessThanOrEqualTo(1)
        }
    }
}
