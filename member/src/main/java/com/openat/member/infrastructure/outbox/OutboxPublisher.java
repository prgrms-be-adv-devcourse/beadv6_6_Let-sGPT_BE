package com.openat.member.infrastructure.outbox;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// outbox 최소 버전 — PENDING row를 주기적으로 읽어 Kafka로 발행, 성공하면 PUBLISHED로 표시.
// 풀(Full) relay/DLQ, "N분 미발행 알림" 등은 지금 범위 밖(payment와 동일하게 최소 버전만 이식).
@Slf4j
@Component
public class OutboxPublisher {

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisher(OutboxEventJpaRepository outboxEventJpaRepository,
            KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 3000)
    @Transactional
    public void publishPending() {
        List<OutboxEventJpaEntity> pending =
                outboxEventJpaRepository.findByStatusOrderByCreatedAtAsc(OutboxEventJpaEntity.Status.PENDING);
        for (OutboxEventJpaEntity event : pending) {
            try {
                // key = aggregateId(= 찜 이벤트의 경우 memberId) — 같은 회원의 이벤트가 같은 파티션에서
                // 순서대로 처리되도록 보장한다.
                kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload()).get();
                event.markPublished();
            } catch (Exception e) {
                log.error("[OutboxPublisher] 발행 실패, 다음 주기에 재시도: topic={}, aggregateId={}",
                        event.getTopic(), event.getAggregateId(), e);
            }
        }
    }
}
