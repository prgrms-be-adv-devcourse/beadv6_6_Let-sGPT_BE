package com.openat.order.domain.model;

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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Getter
@Table(
        name = "order_saga_states",
        uniqueConstraints = @UniqueConstraint(name = "uk_order_saga_states_order_id", columnNames = "order_id"),
        indexes = @Index(name = "idx_order_saga_states_saga_id", columnList = "saga_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderSagaState {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "saga_id", nullable = false, length = 64, updatable = false)
    private String sagaId;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_step", nullable = false, length = 50)
    private SagaStep currentStep;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SagaStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder(builderMethodName = "start")
    private OrderSagaState(UUID orderId, String sagaId) {
        this.orderId = orderId;
        this.sagaId = sagaId;
        this.currentStep = SagaStep.ORDER_CREATED;
        this.status = SagaStatus.IN_PROGRESS;
    }

    public void moveTo(SagaStep currentStep) {
        this.currentStep = currentStep;
    }

    public void complete() {
        this.currentStep = SagaStep.COMPLETED;
        this.status = SagaStatus.COMPLETED;
    }

    public void fail() {
        this.status = SagaStatus.FAILED;
    }

    public void compensate() {
        this.currentStep = SagaStep.COMPENSATING;
        this.status = SagaStatus.COMPENSATING;
    }

    public enum SagaStep {
        ORDER_CREATED,
        STOCK_DECREASED,
        PAYMENT_REQUESTED,
        COMPLETED,
        COMPENSATING,
        COMPENSATION_COMPLETED
    }

    public enum SagaStatus {
        IN_PROGRESS,
        COMPLETED,
        FAILED,
        COMPENSATING
    }
}
