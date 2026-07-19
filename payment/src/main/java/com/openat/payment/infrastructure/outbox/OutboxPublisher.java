package com.openat.payment.infrastructure.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// outbox 최소 버전(A8) — PENDING row를 주기적으로 읽어 Kafka로 발행, 성공하면 PUBLISHED로 표시.
// 풀(Full) relay/DLQ는 파이널 범위(plan.md A8) — 지금은 "N분 미발행 알림"도 아직 없음(Day4에서 확장).
@Slf4j
@Component
public class OutboxPublisher {

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter publishedCounter;

    public OutboxPublisher(OutboxEventJpaRepository outboxEventJpaRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            MeterRegistry meterRegistry) {
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.publishedCounter = meterRegistry.counter("payment.outbox.published");
        Gauge.builder("payment.outbox.pending", outboxEventJpaRepository,
                        repository -> repository.countByStatus(OutboxEventJpaEntity.Status.PENDING))
                .register(meterRegistry);
    }

    @Scheduled(fixedDelay = 3000)
    @Transactional
    public void publishPending() {
        List<OutboxEventJpaEntity> pending =
                outboxEventJpaRepository.findByStatusOrderByCreatedAtAsc(OutboxEventJpaEntity.Status.PENDING);
        for (OutboxEventJpaEntity event : pending) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload()).get();
                event.markPublished();
                publishedCounter.increment();
            } catch (Exception e) {
                log.error("[OutboxPublisher] 발행 실패, 다음 주기에 재시도: topic={}, aggregateId={}",
                        event.getTopic(), event.getAggregateId(), e);
            }
        }
    }
}
