package com.openat.product.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
@Table(
    name = "product_outbox_events",
    indexes = {
      @Index(
          name = "idx_product_outbox_status_created",
          columnList = "status, created_at"),
      @Index(
          name = "idx_product_outbox_aggregate_sequence",
          columnList = "aggregate_id, aggregate_sequence")
    },
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_product_outbox_aggregate_sequence",
            columnNames = {"aggregate_id", "aggregate_sequence"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductOutboxEvent {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.TIME)
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "aggregate_id", nullable = false, updatable = false)
  private UUID aggregateId;

  @Column(name = "aggregate_sequence", nullable = false, updatable = false)
  private long aggregateSequence;

  @Column(nullable = false, length = 100, updatable = false)
  private String topic;

  @Column(nullable = false, columnDefinition = "text", updatable = false)
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ProductOutboxEventStatus status;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "claimed_at")
  private Instant claimedAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Builder(builderMethodName = "record")
  private ProductOutboxEvent(
      UUID aggregateId, long aggregateSequence, String topic, String payload) {
    this.aggregateId = aggregateId;
    this.aggregateSequence = aggregateSequence;
    this.topic = topic;
    this.payload = payload;
    this.status = ProductOutboxEventStatus.PENDING;
  }

  public void markProcessing(Instant claimedAt) {
    this.status = ProductOutboxEventStatus.PROCESSING;
    this.claimedAt = claimedAt;
  }
}
