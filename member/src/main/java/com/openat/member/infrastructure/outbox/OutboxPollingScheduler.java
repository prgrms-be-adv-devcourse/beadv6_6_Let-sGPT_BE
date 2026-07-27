package com.openat.member.infrastructure.outbox;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PENDING outbox 행을 주기적으로 읽어 {@link OutboxEventPublisher}에 1건씩 위임한다.
 *
 * <p>이 클래스 자체엔 {@code @Transactional}을 걸지 않는다 — 이벤트 1건의 발행/커밋은
 * {@code OutboxEventPublisher.publish()}가 {@code REQUIRES_NEW}로 독립 처리하므로, 여기서는
 * DB 커넥션을 쥐지 않는다. 조회에도 {@code BATCH_SIZE} 상한을 둬 적체가 쌓여도 한 tick이
 * 무한정 길어지지 않게 한다(order의 {@code OutboxPollingScheduler}와 동일 패턴).
 *
 * <p>배치 중 한 건이 예기치 못한 런타임 예외를 던져도 나머지 건 처리를 계속한다 — 개별 이벤트는
 * 이미 별도 트랜잭션이라 서로 영향을 주지 않는다.
 */
@Slf4j
@Component
public class OutboxPollingScheduler {

    private static final int BATCH_SIZE = 100;

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final OutboxEventPublisher outboxEventPublisher;

    public OutboxPollingScheduler(OutboxEventJpaRepository outboxEventJpaRepository,
            OutboxEventPublisher outboxEventPublisher) {
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.outboxEventPublisher = outboxEventPublisher;
    }

    @Scheduled(fixedDelay = 3000)
    public void publishPending() {
        List<OutboxEventJpaEntity> pending = outboxEventJpaRepository.findByStatusOrderByCreatedAtAsc(
                OutboxEventJpaEntity.Status.PENDING, PageRequest.of(0, BATCH_SIZE));
        for (OutboxEventJpaEntity event : pending) {
            try {
                outboxEventPublisher.publish(event.getId());
            } catch (RuntimeException exception) {
                log.error("[OutboxPollingScheduler] 예기치 못한 발행 실패, 배치 계속 진행: outboxEventId={}",
                        event.getId(), exception);
            }
        }
    }
}
