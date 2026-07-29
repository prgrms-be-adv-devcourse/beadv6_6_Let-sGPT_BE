package com.openat.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Getter
@Table(name = "outbox_events")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "topic", nullable = false, length = 100, updatable = false)
    private String topic;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String payload;

    // 적재 시점의 원 요청 W3C traceparent. 폴링 발행 시 이 값으로 문맥을 복원해 producer 스팬을
    // 원 요청 트레이스에 잇는다. nullable — 트레이스 비활성 경로/기존 행은 종전대로 발행된다.
    @Column(name = "trace_parent", length = 64, updatable = false)
    private String traceParent;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxEventStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Builder(builderMethodName = "create")
    private OutboxEvent(String topic, String payload, String traceParent) {
        this.topic = topic;
        this.payload = payload;
        this.traceParent = traceParent;
        this.status = OutboxEventStatus.PENDING;
    }

    public void markFailed() {
        this.status = OutboxEventStatus.FAILED;
        this.publishedAt = null;
    }
}
