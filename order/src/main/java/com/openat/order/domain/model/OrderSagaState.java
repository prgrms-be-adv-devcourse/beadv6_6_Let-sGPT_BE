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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Getter
@Table(
    name = "order_saga_states",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_order_saga_states_order_id", columnNames = "order_id")
    })
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
  private OrderSagaStep currentStep;

  @Column(name = "compensating_since")
  private Instant compensatingSince;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Builder(builderMethodName = "create")
  private OrderSagaState(UUID orderId, String sagaId) {
    this.orderId = orderId;
    this.sagaId = sagaId;
    this.currentStep = OrderSagaStep.ORDER_CREATED;
  }

  public void advanceTo(OrderSagaStep step) {
    this.currentStep = step;
  }

  public void enterCompensating() {
    this.currentStep = OrderSagaStep.COMPENSATING;
    if (this.compensatingSince == null) {
      this.compensatingSince = Instant.now();
    }
  }
}
