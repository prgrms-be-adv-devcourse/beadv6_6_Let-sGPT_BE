package com.openat.queue.infrastructure.persistence

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.ReactiveStringRedisTemplate
import org.springframework.stereotype.Component

/**
 * WebFlux+SSE 전환분: dropId의 대기열 상태가 바뀔 수 있는 시점에 Redis Pub/Sub으로 "변경됨"
 * 신호를 쏜다. 이 컴포넌트가 유일한 발행 지점이고, 호출부는 전부 기존 블로킹
 * [WaitingQueueRepository] 뮤테이션 직후다(스케줄러/유스케이스 계층 - 리포지토리 자체는
 * 손대지 않는다).
 *
 * best-effort다: publish가 실패해도 예외를 던지지 않는다(로그만 남김) - SSE 쪽은 keep-alive
 * 틱마다 자체적으로도 재확인하므로(QueueStreamService), 이 신호 하나를 놓쳐도 클라이언트가
 * 영영 갱신을 못 받는 게 아니라 다음 keep-alive 주기 안에는 따라잡는다.
 */
@Component
class QueueEventPublisher(
    private val reactiveRedisTemplate: ReactiveStringRedisTemplate,
) {
    private val log = LoggerFactory.getLogger(QueueEventPublisher::class.java)

    fun publishChanged(dropId: String) {
        reactiveRedisTemplate.convertAndSend(RedisKeys.eventsChannel(dropId), "changed")
            .subscribe(
                { },
                { e -> log.warn("[queue-events] dropId={} 발행 실패(최선노력이라 무시) : {}", dropId, e.toString()) },
            )
    }
}
