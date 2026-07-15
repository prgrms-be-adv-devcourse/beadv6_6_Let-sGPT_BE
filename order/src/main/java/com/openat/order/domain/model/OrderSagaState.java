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

// [파이널] 주문 생성 사가의 단계를 추적한다. currentStep이 단일 SoT이며 별도 status 컬럼은 두지 않는다
// (설계문서 `주문_파이널_설계.md` §2.1 S4 확정 — 전체 주문 상태의 SoT는 Order.status).
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

    // [파이널] COMPENSATING에 최초 진입한 시각. 재시도 실패로 updatedAt이 계속 갱신돼도
    // 이 값은 건드리지 않아, §10 체류 경보(예: 10분 초과)가 정확한 경과 시간을 계산할 수 있게 한다.
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

    // COMPENSATING 진입 — compensatingSince는 최초 1회만 기록(재진입해도 덮어쓰지 않음).
    public void enterCompensating() {
        this.currentStep = OrderSagaStep.COMPENSATING;
        if (this.compensatingSince == null) {
            this.compensatingSince = Instant.now();
        }
    }
}
