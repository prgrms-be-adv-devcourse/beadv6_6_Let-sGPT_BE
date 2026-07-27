package com.openat.member.infrastructure.outbox;

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
 * 배치 전체를 순회하는 동안 이 메서드는 각자 독립된 트랜잭션/커넥션을 쓰므로, Kafka 응답을 기다리는
 * 동안 DB 커넥션을 오래 붙들지 않고, 배치 중간에 다른 이벤트가 실패해도 이미 처리된 이벤트의 커밋이
 * 함께 롤백되지 않는다(order의 {@code OutboxEventPublisher}와 동일 패턴).
 *
 * <p>이전 버전(구 {@code OutboxPublisher})은 배치 전체를 하나의 {@code @Transactional}로 묶고
 * Kafka 응답을 {@code .get()}으로 무제한 대기해, 적체 시 DB 커넥션 풀을 고갈시킬 수 있다는 리뷰
 * 지적을 받았다 — 이 클래스가 그 대체다.
 */
@Slf4j
@Component
public class OutboxEventPublisher {

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxEventPublisher(OutboxEventJpaRepository outboxEventJpaRepository,
            KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxEventJpaRepository = outboxEventJpaRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(UUID outboxEventId) {
        OutboxEventJpaEntity event = outboxEventJpaRepository.findById(outboxEventId).orElse(null);
        // 스케줄러가 조회한 시점과 이 트랜잭션이 실제로 여는 시점 사이에 이미 처리됐을 수 있어
        // (다음 tick과 겹치는 경우 등) 상태를 다시 확인한다.
        if (event == null || event.getStatus() != OutboxEventJpaEntity.Status.PENDING) {
            return;
        }

        try {
            // key = aggregateId — 같은 aggregate(찜은 memberId, 판매자 스토어는 sellerInfoId)의
            // 이벤트가 항상 같은 파티션에서 순서대로 처리되도록 보장한다.
            kafkaTemplate.send(event.getTopic(), event.getAggregateId().toString(), event.getPayload())
                    .get(5, TimeUnit.SECONDS);
            event.markPublished();
            log.info("[OutboxEventPublisher] 발행 성공: topic={}, aggregateId={}",
                    event.getTopic(), event.getAggregateId());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("[OutboxEventPublisher] 발행 중 인터럽트, PENDING 유지: topic={}, aggregateId={}",
                    event.getTopic(), event.getAggregateId(), exception);
        } catch (Exception exception) {
            log.error("[OutboxEventPublisher] 발행 실패, 다음 주기에 재시도: topic={}, aggregateId={}",
                    event.getTopic(), event.getAggregateId(), exception);
        }
    }
}
