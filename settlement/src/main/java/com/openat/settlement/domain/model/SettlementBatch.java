package com.openat.settlement.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(name = "settlement_batchs")
@Comment("정산 배치 실행 이력 및 상태 관리")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementBatch {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Comment("정산 배치 ID(UUID)")
    @Column(name = "batch_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID id;

    @Comment("정산월(YYYYMM)")
    @Column(name = "settlement_month", nullable = false, length = 6)
    private String settlementMonth;

    @Enumerated(EnumType.STRING)
    @Comment("배치 유형(LOAD_PAYMENT, LOAD_REFUND, SETTLEMENT_RUN)")
    @Column(name = "batch_type", nullable = false, length = 30)
    private SettlementBatchType batchType;

    @Enumerated(EnumType.STRING)
    @Comment("배치 상태(READY, RUNNING, COMPLETED, FAILED)")
    @Column(name = "status", nullable = false, length = 20)
    private SettlementBatchStatus status;

    @Comment("배치 시작 일시")
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Comment("배치 종료 일시")
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Comment("적재 또는 정산 주문 건수")
    @Column(name = "total_order_count", nullable = false, columnDefinition = "INT")
    private Integer totalOrderCount;

    @Comment("정산 대상 판매자 수")
    @Column(name = "total_seller_count", nullable = false, columnDefinition = "INT")
    private Integer totalSellerCount;

    @Comment("총 정산 금액")
    @Column(name = "total_settlement_amount", nullable = false, columnDefinition = "BIGINT")
    private Long totalSettlementAmount;

    @Comment("배치 실패 사유")
    @Column(name = "fail_reason", length = 500)
    private String failReason;

    @Comment("생성 일시")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static SettlementBatch create(
            String settlementMonth,
            SettlementBatchType batchType
    ) {
        SettlementBatch batch = new SettlementBatch();
        batch.settlementMonth = settlementMonth;
        batch.batchType = batchType;
        batch.status = SettlementBatchStatus.READY;
        batch.totalOrderCount = 0;
        batch.totalSellerCount = 0;
        batch.totalSettlementAmount = 0L;
        return batch;
    }

    public void start() {
        this.status = SettlementBatchStatus.RUNNING;
        this.startedAt = LocalDateTime.now();
        this.failReason = null;
    }

    public void complete(
            int totalOrderCount,
            int totalSellerCount,
            long totalSettlementAmount
    ) {
        this.status = SettlementBatchStatus.COMPLETED;
        this.totalOrderCount = totalOrderCount;
        this.totalSellerCount = totalSellerCount;
        this.totalSettlementAmount = totalSettlementAmount;
        this.endedAt = LocalDateTime.now();
    }

    public void fail(String failReason) {
        this.status = SettlementBatchStatus.FAILED;
        this.failReason = failReason;
        this.endedAt = LocalDateTime.now();
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();

        if (this.status == null) {
            this.status = SettlementBatchStatus.READY;
        }

        if (this.totalOrderCount == null) {
            this.totalOrderCount = 0;
        }

        if (this.totalSellerCount == null) {
            this.totalSellerCount = 0;
        }

        if (this.totalSettlementAmount == null) {
            this.totalSettlementAmount = 0L;
        }
    }
}
