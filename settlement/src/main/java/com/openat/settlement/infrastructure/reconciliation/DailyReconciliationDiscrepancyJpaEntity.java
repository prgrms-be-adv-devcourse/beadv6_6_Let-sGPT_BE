package com.openat.settlement.infrastructure.reconciliation;

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
import org.hibernate.annotations.UuidGenerator;

// 정산 대사 불일치(settlement 담당 처리 대상, WS-0.4) — payment의 reconciliation_discrepancies와 대칭 구조.
@Entity
@Table(name = "daily_reconciliation_discrepancies")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyReconciliationDiscrepancyJpaEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private EntityType entityType;

    @Column(name = "reference_id", nullable = false, columnDefinition = "uuid")
    private UUID referenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", nullable = false, length = 30)
    private DiscrepancyType discrepancyType;

    @Column(length = 500)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public DailyReconciliationDiscrepancyJpaEntity(LocalDate businessDate, EntityType entityType,
            UUID referenceId, DiscrepancyType discrepancyType, String detail) {
        this.businessDate = businessDate;
        this.entityType = entityType;
        this.referenceId = referenceId;
        this.discrepancyType = discrepancyType;
        this.detail = detail;
        this.createdAt = LocalDateTime.now();
    }

    public enum EntityType {
        ORDER, REFUND
    }

    public enum DiscrepancyType {
        MISSING_IN_SETTLEMENT, AMOUNT_MISMATCH, NULL_SELLER
    }
}
