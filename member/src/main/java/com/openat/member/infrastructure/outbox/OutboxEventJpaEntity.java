package com.openat.member.infrastructure.outbox;

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
import org.hibernate.annotations.UuidGenerator;

/**
 * 아웃박스 이벤트(최소 버전) — payment 모듈의 outbox 패턴을 member에 이식.
 * 도메인 쓰기와 같은 트랜잭션으로 이 행을 적재하고, {@link OutboxPublisher}가 별도 스케줄러로
 * Kafka에 발행한 뒤 PUBLISHED로 표시한다. dual-write(DB 커밋은 됐는데 Kafka 발행만 실패)로 인한
 * 이벤트 유실을 방지하기 위함이다.
 *
 * <p>{@code aggregateId}는 Kafka 발행 key로도 쓰인다(payment와 동일 컨벤션: key = aggregateId).
 * 찜 이벤트는 "같은 회원의 찜 변경은 항상 순서대로 처리돼야 한다"는 요건 때문에
 * WishlistItem의 id가 아니라 memberId(userId)를 aggregateId로 사용한다 — 그래야 삭제 후 재생성으로
 * 매번 새 WishlistItem이 만들어져도 같은 파티션에 쌓여 순서가 보존된다.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEventJpaEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 30)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    public OutboxEventJpaEntity(String aggregateType, UUID aggregateId, String topic, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.payload = payload;
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
