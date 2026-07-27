package com.openat.payment.infrastructure.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PENDING outbox 행을 주기적으로 읽어 {@link OutboxEventPublisher}에 1건씩 위임한다.
 *
 * <p>이 클래스에는 {@code @Transactional}을 걸지 않는다 — 발행/상태 갱신은 건별로
 * {@code OutboxEventPublisher.publish()}가 {@code REQUIRES_NEW}로 처리하므로, 스케줄러는
 * 조회 트랜잭션이 끝난 뒤 커넥션을 쥐고 있지 않다. 조회에는 {@code BATCH_SIZE} 상한을 둬
 * 적체가 쌓여도 한 tick이 무한정 길어지지 않게 한다(order/member와 동일 패턴).
 *
 * <p>한 건이 예기치 못한 런타임 예외를 던져도 나머지 건 처리를 계속한다 — 실패한 이벤트는
 * PENDING으로 남아 다음 주기에 재시도된다.
 */
@Slf4j
@Component
public class OutboxPollingScheduler {

    private static final int BATCH_SIZE = 100;

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final OutboxEventPublisher outboxEventPublisher;

    public OutboxPollingScheduler(OutboxEventJpaRepository outboxEventJpaRepository,
            OutboxEventPublisher outboxEventPublisher,
            MeterRegistry meterRegistry) {
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.outboxEventPublisher = outboxEventPublisher;
        Gauge.builder("payment.outbox.pending", outboxEventJpaRepository,
                        repository -> repository.countByStatus(OutboxEventJpaEntity.Status.PENDING))
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 3000)
    public void publishPending() {
        List<OutboxEventJpaEntity> pending = outboxEventJpaRepository.findByStatusOrderByCreatedAtAsc(
                OutboxEventJpaEntity.Status.PENDING, PageRequest.of(0, BATCH_SIZE));
        for (OutboxEventJpaEntity event : pending) {
            try {
                outboxEventPublisher.publish(event.getId());
            } catch (RuntimeException e) {
                log.error("[OutboxPollingScheduler] 예기치 못한 발행 실패, 배치 계속 진행: outboxEventId={}",
                        event.getId(), e);
            }
        }
    }
}
