package com.openat.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
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
    name = "inbox_events",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_inbox_events_event_id", columnNames = "event_id")
    })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InboxEvent {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.TIME)
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "event_id", nullable = false, length = 100, updatable = false)
  private String eventId;

  @Column(name = "event_type", nullable = false, length = 100, updatable = false)
  private String eventType;

  @Column(name = "payload", nullable = false, columnDefinition = "TEXT", updatable = false)
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private InboxEventStatus status;

  @Column(name = "error_message", length = 500)
  private String errorMessage;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "processed_at")
  private Instant processedAt;

  @Builder(builderMethodName = "receive")
  private InboxEvent(String eventId, String eventType, String payload) {
    this.eventId = eventId;
    this.eventType = eventType;
    this.payload = payload;
    this.status = InboxEventStatus.RECEIVED;
  }

  public void retry() {
    this.status = InboxEventStatus.RECEIVED;
    this.errorMessage = null;
    this.processedAt = null;
  }

  public void markProcessed(Instant processedAt) {
    this.status = InboxEventStatus.PROCESSED;
    this.errorMessage = null;
    this.processedAt = processedAt;
  }

  public void markFailed(String errorMessage) {
    this.status = InboxEventStatus.FAILED;
    this.errorMessage =
        errorMessage == null
            ? null
            : errorMessage.substring(0, Math.min(500, errorMessage.length()));
    this.processedAt = null;
  }
}
