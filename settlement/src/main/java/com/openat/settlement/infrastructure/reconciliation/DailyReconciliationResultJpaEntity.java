package com.openat.settlement.infrastructure.reconciliation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

// 정산 대사(reconciliation.md, WS-3) 실행 결과 — outbox_events/reconciliation_discrepancies(payment)와 동일하게
// 감사로그 성격이라 도메인 포트 없이 JPA를 직접 사용한다. ddl-auto=update로 자동 생성(settlement는 flyway 미사용).
@Entity
@Table(name = "daily_reconciliation_results", uniqueConstraints = {
        @UniqueConstraint(name = "uk_daily_reconciliation_business_date", columnNames = "business_date")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyReconciliationResultJpaEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "business_date", nullable = false)
    private LocalDate businessDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "payment_count", nullable = false)
    private int paymentCount;

    @Column(name = "total_payment_amount", nullable = false)
    private long totalPaymentAmount;

    @Column(name = "refund_count", nullable = false)
    private int refundCount;

    @Column(name = "total_refund_amount", nullable = false)
    private long totalRefundAmount;

    @Column(name = "expected_settlement_amount", nullable = false)
    private long expectedSettlementAmount;

    @Column(name = "discrepancy_count", nullable = false)
    private int discrepancyCount;

    @Column(name = "executed_at", nullable = false)
    private LocalDateTime executedAt;

    public DailyReconciliationResultJpaEntity(LocalDate businessDate, Status status, int paymentCount,
            long totalPaymentAmount, int refundCount, long totalRefundAmount, long expectedSettlementAmount,
            int discrepancyCount) {
        this.businessDate = businessDate;
        this.status = status;
        this.paymentCount = paymentCount;
        this.totalPaymentAmount = totalPaymentAmount;
        this.refundCount = refundCount;
        this.totalRefundAmount = totalRefundAmount;
        this.expectedSettlementAmount = expectedSettlementAmount;
        this.discrepancyCount = discrepancyCount;
        this.executedAt = LocalDateTime.now();
    }

    // 같은 businessDate 재-pull(hold 재유입, WS-0.5) 시 이전 결과를 최신 값으로 덮어쓴다.
    public void overwriteWith(Status status, int paymentCount, long totalPaymentAmount, int refundCount,
            long totalRefundAmount, long expectedSettlementAmount, int discrepancyCount) {
        this.status = status;
        this.paymentCount = paymentCount;
        this.totalPaymentAmount = totalPaymentAmount;
        this.refundCount = refundCount;
        this.totalRefundAmount = totalRefundAmount;
        this.expectedSettlementAmount = expectedSettlementAmount;
        this.discrepancyCount = discrepancyCount;
        this.executedAt = LocalDateTime.now();
    }

    public enum Status {
        SUCCESS, DISCREPANCY_FOUND, CALL_FAILED
    }
}
