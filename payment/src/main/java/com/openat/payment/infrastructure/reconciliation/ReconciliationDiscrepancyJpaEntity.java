package com.openat.payment.infrastructure.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// PG 대사(WS-0) 불일치 기록 — outbox_events와 동일하게 감사로그 성격이라 도메인 포트 없이 JPA를 직접 사용한다.
@Entity
@Table(name = "reconciliation_discrepancies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReconciliationDiscrepancyJpaEntity {

    @Id
    private UUID id;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private EntityType entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", nullable = false, length = 30)
    private DiscrepancyType discrepancyType;

    @Column(length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ReconciliationDiscrepancyJpaEntity(UUID id, LocalDate businessDate, EntityType entityType,
            UUID entityId, DiscrepancyType discrepancyType, String detail) {
        this.id = id;
        this.businessDate = businessDate;
        this.entityType = entityType;
        this.entityId = entityId;
        this.discrepancyType = discrepancyType;
        this.detail = detail;
        this.createdAt = LocalDateTime.now();
    }

    public enum EntityType {
        PAYMENT, REFUND
    }

    public enum DiscrepancyType {
        NOT_FOUND_IN_PG, STATUS_MISMATCH, AMOUNT_MISMATCH
    }
}
