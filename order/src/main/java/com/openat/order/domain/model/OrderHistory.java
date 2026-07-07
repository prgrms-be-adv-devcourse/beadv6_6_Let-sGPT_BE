package com.openat.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
@Table(
        name = "order_histories",
        indexes = {
            @Index(name = "idx_order_histories_order_id", columnList = "order_id"),
            @Index(name = "idx_order_histories_source_event_key", columnList = "source_event_key")
        })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderHistory {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_status", length = 30)
    private OrderStatus previousStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_status", nullable = false, length = 30)
    private OrderStatus newStatus;

    @Column(name = "reason_code", length = 50)
    private String reasonCode;

    @Column(name = "reason_message")
    private String reasonMessage;

    @Column(name = "source_event_key", length = 100)
    private String sourceEventKey;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Builder(builderMethodName = "record")
    private OrderHistory(
            UUID orderId,
            OrderStatus previousStatus,
            OrderStatus newStatus,
            String reasonCode,
            String reasonMessage,
            String sourceEventKey) {
        this.orderId = orderId;
        this.previousStatus = previousStatus;
        this.newStatus = newStatus;
        this.reasonCode = reasonCode;
        this.reasonMessage = reasonMessage;
        this.sourceEventKey = sourceEventKey;
    }
}
