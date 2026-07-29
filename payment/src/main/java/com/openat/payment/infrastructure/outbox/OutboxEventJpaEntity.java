package com.openat.payment.infrastructure.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// outbox 최소 버전 — DB 커밋과 같은 트랜잭션으로 적재, 별도 스케줄러(OutboxPollingScheduler)가 발행.
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEventJpaEntity {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 30)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    // 적재 시점의 원 요청 W3C traceparent. 폴링 발행 시 이 값으로 문맥을 복원해 producer 스팬을
    // 원 요청 트레이스에 잇는다. nullable — 트레이스 비활성 경로/기존 행은 종전대로 발행된다.
    @Column(name = "trace_parent", length = 64)
    private String traceParent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    public OutboxEventJpaEntity(UUID id, String aggregateType, UUID aggregateId, String topic, String payload) {
        this(id, aggregateType, aggregateId, topic, payload, null);
    }

    public OutboxEventJpaEntity(UUID id, String aggregateType, UUID aggregateId, String topic, String payload,
            String traceParent) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.payload = payload;
        this.traceParent = traceParent;
        this.status = Status.PENDING;
        this.createdAt = LocalDateTime.now();
    }

    public void markPublished() {
        this.status = Status.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    public enum Status {
        PENDING, PUBLISHED
    }
}
