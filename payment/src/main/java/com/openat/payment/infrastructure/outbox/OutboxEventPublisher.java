package com.openat.payment.infrastructure.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 이벤트 1건을 발행한다.
 *
 * <p>이벤트 1건 단위로 트랜잭션을 새로 연다({@code REQUIRES_NEW}) — {@link OutboxPollingScheduler}가
 * 배치를 순회하는 동안 각 건은 독립된 짧은 트랜잭션/커넥션을 쓴다. 이전 버전(구 {@code OutboxPublisher})은
 * 배치 전체를 하나의 {@code @Transactional}로 묶고 Kafka 응답을 타임아웃 없이 기다려, 사이클 전체
 * (측정값 5.9~7.8초) 동안 DB 커넥션 1개를 계속 점유했다.
 *
 * <p>스케줄러가 아니라 이 별도 빈에 트랜잭션을 거는 이유는 같은 클래스 안에서 호출하면
 * 프록시를 타지 않아(self-invocation) {@code REQUIRES_NEW}가 적용되지 않기 때문이다.
 */
@Slf4j
@Component
public class OutboxEventPublisher {

    private static final long SEND_TIMEOUT_SECONDS = 5;

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter publishedCounter;

    public OutboxEventPublisher(OutboxEventJpaRepository outboxEventJpaRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry) {
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.publishedCounter = meterRegistry.counter("payment.outbox.published");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(UUID outboxEventId) {
        OutboxEventJpaEntity event = outboxEventJpaRepository.findById(outboxEventId).orElse(null);
        // 스케줄러가 조회한 시점과 이 트랜잭션을 여는 시점 사이에 이미 처리됐을 수 있어 상태를 다시 확인한다.
        if (event == null || event.getStatus() != OutboxEventJpaEntity.Status.PENDING) {
            return;
        }

        try {
            // key = aggregateId — 같은 aggregate의 이벤트가 같은 파티션에서 순서대로 처리되도록 한다.
            kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            event.markPublished();
            publishedCounter.increment();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[OutboxEventPublisher] 발행 중 인터럽트, PENDING 유지: topic={}, aggregateId={}",
                    event.getTopic(), event.getAggregateId(), e);
        } catch (Exception e) {
            log.error("[OutboxEventPublisher] 발행 실패, 다음 주기에 재시도: topic={}, aggregateId={}",
                    event.getTopic(), event.getAggregateId(), e);
        }
    }
}
