package com.openat.payment.infrastructure.outbox;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * PENDING outbox 행을 주기적으로 Kafka에 발행한다.
 *
 * <p>한 사이클에서 DB 커넥션을 쓰는 구간은 두 곳뿐이다 — 시작 시점의 PENDING 조회 1회와,
 * ack를 받은 뒤의 확정 UPDATE 1회. 그 사이 Kafka 전송·응답 대기 구간에는 트랜잭션도 커넥션도
 * 잡지 않는다. 이전 구조는 발행 대기를 트랜잭션 안에서 했기 때문에 브로커가 느려지면 그만큼
 * 커넥션이 묶였다(배치 전체를 한 트랜잭션으로 묶었을 땐 사이클 전체, 건별 트랜잭션으로 쪼갠
 * 뒤에도 건당 대기 시간만큼).
 *
 * <p>발행은 배치 전량을 비동기로 먼저 쏘고({@code send}는 즉시 반환) 나중에 ack를 모아 확정한다.
 * 같은 key(aggregateId)는 같은 파티션에 발사한 순서대로 들어가므로 순서 의도는 유지된다.
 * ack를 못 받은 건은 PENDING으로 남아 다음 주기에 재시도되며, 개별 실패가 다른 이벤트의 확정을
 * 막지 않는다.
 */
@Slf4j
@Component
public class OutboxPollingScheduler {

    private static final int BATCH_SIZE = 100;
    // 배치 전체의 ack 대기 상한. 이 시간을 넘긴 건은 PENDING으로 남기고 다음 주기에 다시 시도한다.
    private static final Duration ACK_TIMEOUT = Duration.ofSeconds(10);

    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Counter publishedCounter;

    public OutboxPollingScheduler(OutboxEventJpaRepository outboxEventJpaRepository,
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
    public void publishPending() {
        List<PendingEvent> pending = fetchPending();
        if (pending.isEmpty()) {
            return;
        }
        List<InFlight> inFlight = sendAll(pending);
        List<UUID> acked = awaitAcks(inFlight);
        confirmPublished(acked);
    }

    // 조회 트랜잭션은 이 메서드 안에서 끝난다 — 반환 시점엔 커넥션이 이미 반납돼 있다.
    private List<PendingEvent> fetchPending() {
        return outboxEventJpaRepository
                .findByStatusOrderByCreatedAtAsc(OutboxEventJpaEntity.Status.PENDING,
                        PageRequest.of(0, BATCH_SIZE))
                .stream()
                .map(event -> new PendingEvent(event.getId(), event.getTopic(),
                        event.getAggregateId().toString(), event.getPayload()))
                .toList();
    }

    // 배치 전량을 비동기로 발사한다. key = aggregateId — 같은 aggregate의 이벤트는 같은 파티션에
    // 발사한 순서대로 들어간다.
    private List<InFlight> sendAll(List<PendingEvent> pending) {
        List<InFlight> inFlight = new ArrayList<>(pending.size());
        for (PendingEvent event : pending) {
            try {
                inFlight.add(new InFlight(event,
                        kafkaTemplate.send(event.topic(), event.key(), event.payload())));
            } catch (Exception e) {
                log.error("[OutboxPollingScheduler] 발행 요청 실패, 다음 주기에 재시도: topic={}, aggregateId={}",
                        event.topic(), event.key(), e);
            }
        }
        return inFlight;
    }

    // 배치 전체에 하나의 마감 시각을 두고 ack를 모은다. 대기 중에는 DB 커넥션을 쥐지 않는다.
    private List<UUID> awaitAcks(List<InFlight> inFlight) {
        long deadline = System.nanoTime() + ACK_TIMEOUT.toNanos();
        List<UUID> acked = new ArrayList<>(inFlight.size());
        for (InFlight sent : inFlight) {
            PendingEvent event = sent.event();
            try {
                sent.future().get(Math.max(deadline - System.nanoTime(), 0), TimeUnit.NANOSECONDS);
                acked.add(event.id());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[OutboxPollingScheduler] 발행 대기 중 인터럽트, 남은 건은 PENDING 유지: topic={}, aggregateId={}",
                        event.topic(), event.key(), e);
                break;
            } catch (TimeoutException e) {
                log.warn("[OutboxPollingScheduler] 발행 응답 지연, 다음 주기에 재시도: topic={}, aggregateId={}",
                        event.topic(), event.key(), e);
            } catch (Exception e) {
                log.error("[OutboxPollingScheduler] 발행 실패, 다음 주기에 재시도: topic={}, aggregateId={}",
                        event.topic(), event.key(), e);
            }
        }
        return acked;
    }

    // ack 받은 id만 한 번의 짧은 트랜잭션으로 확정한다. 조건부 UPDATE라 이미 확정된 행은 세지 않는다.
    private void confirmPublished(List<UUID> acked) {
        if (acked.isEmpty()) {
            return;
        }
        int updated = outboxEventJpaRepository.markPublished(acked,
                OutboxEventJpaEntity.Status.PUBLISHED,
                OutboxEventJpaEntity.Status.PENDING,
                LocalDateTime.now());
        if (updated > 0) {
            publishedCounter.increment(updated);
        }
    }

    private record PendingEvent(UUID id, String topic, String key, String payload) {
    }

    private record InFlight(PendingEvent event, CompletableFuture<SendResult<String, String>> future) {
    }
}
