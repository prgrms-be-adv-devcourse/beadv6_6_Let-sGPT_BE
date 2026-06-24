package com.openat.settlement.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Entity
@Table(
        name = "seller_settlements",
        indexes = {
                @Index(
                        name = "idx_seller_settlement_seller_month",
                        columnList = "seller_id, settlement_month"
                ),
                @Index(
                        name = "idx_seller_settlement_batch_id",
                        columnList = "batch_id"
                )
        },
        uniqueConstraints = {
                @UniqueConstraint(  // sellerId + settlementMonth UNIQUE 제약조건으로 중복 정산 방지
                        name = "uk_seller_settlement_seller_month",
                        columnNames = {"seller_id", "settlement_month"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerSettlement {

    private static final int MAX_FAIL_REASON_LENGTH = 500;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Column(name = "seller_settlement_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "batch_id", nullable = false, columnDefinition = "uuid")
    private UUID batchId;

    @Column(name = "settlement_month", nullable = false, length = 6)
    private String settlementMonth;

    @Column(name = "seller_id", nullable = false, columnDefinition = "uuid")
    private UUID sellerId;

    @Column(name = "total_order_count", nullable = false)
    private Integer totalOrderCount;

    @Column(name = "total_paid_amount", nullable = false)
    private Long totalPaidAmount;

    @Column(name = "total_fee_amount", nullable = false)
    private Long totalFeeAmount;

    @Column(name = "total_refund_amount", nullable = false)
    private Long totalRefundAmount;

    @Column(name = "total_adjustment_amount", nullable = false)
    private Long totalAdjustmentAmount;

    @Column(name = "final_settlement_amount", nullable = false)
    private Long finalSettlementAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SellerSettlementStatus status;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "fail_reason", length = MAX_FAIL_REASON_LENGTH)
    private String failReason;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    public static SellerSettlement create(
            UUID batchId,
            String settlementMonth,
            UUID sellerId,
            int totalOrderCount,
            long totalPaidAmount,
            long totalFeeAmount,
            long totalRefundAmount,
            long totalAdjustmentAmount
    ) {
        SellerSettlement settlement = new SellerSettlement();
        settlement.batchId = batchId;
        settlement.settlementMonth = settlementMonth;
        settlement.sellerId = sellerId;
        settlement.totalOrderCount = totalOrderCount;
        settlement.totalPaidAmount = totalPaidAmount;
        settlement.totalFeeAmount = totalFeeAmount;
        settlement.totalRefundAmount = totalRefundAmount;
        settlement.totalAdjustmentAmount = totalAdjustmentAmount;
        settlement.finalSettlementAmount =
                totalPaidAmount
                        - totalFeeAmount
                        - totalRefundAmount
                        + totalAdjustmentAmount;
        settlement.status = SellerSettlementStatus.READY;
        return settlement;
    }

    /**
     * 실패 또는 재처리 상황에서 기존 sellerId + settlementMonth 행을 다시 계산값으로 갱신합니다.
     *
     * seller_id + settlement_month UNIQUE 제약조건 때문에 동일 판매자/동일 월은 새 row를 만들 수 없습니다.
     * 따라서 FAILED 상태를 재처리할 때는 기존 row의 금액을 갱신한 뒤 complete()를 호출합니다.
     */
    public void recalculate(
            UUID batchId,
            int totalOrderCount,
            long totalPaidAmount,
            long totalFeeAmount,
            long totalRefundAmount,
            long totalAdjustmentAmount
    ) {
        this.batchId = batchId;
        this.totalOrderCount = totalOrderCount;
        this.totalPaidAmount = totalPaidAmount;
        this.totalFeeAmount = totalFeeAmount;
        this.totalRefundAmount = totalRefundAmount;
        this.totalAdjustmentAmount = totalAdjustmentAmount;
        this.finalSettlementAmount =
                totalPaidAmount
                        - totalFeeAmount
                        - totalRefundAmount
                        + totalAdjustmentAmount;
        this.status = SellerSettlementStatus.READY;
        this.completedAt = null;
        this.failReason = null;
        this.failedAt = null;
    }

    public boolean isFailed() {
        return this.status == SellerSettlementStatus.FAILED;
    }

    public boolean isReady() {
        return this.status == SellerSettlementStatus.READY;
    }

    public Long getFinalSettlementAmountOrZero() {
        return this.finalSettlementAmount == null ? 0L : this.finalSettlementAmount;
    }

    public Integer getTotalOrderCountOrZero() {
        return this.totalOrderCount == null ? 0 : this.totalOrderCount;
    }

    public void complete() {
        this.status = SellerSettlementStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
        this.failReason = null;
        this.failedAt = null;
    }

    public void fail(UUID batchId, String failReason) {
        this.batchId = batchId;
        this.status = SellerSettlementStatus.FAILED;
        this.failReason = truncateFailReason(failReason);
        this.failedAt = LocalDateTime.now();
    }

    public boolean isCompleted() {
        return this.status == SellerSettlementStatus.COMPLETED;
    }

    private String truncateFailReason(String failReason) {
        if (failReason == null || failReason.isBlank()) {
            return null;
        }

        return failReason.length() <= MAX_FAIL_REASON_LENGTH
                ? failReason
                : failReason.substring(0, MAX_FAIL_REASON_LENGTH);
    }
}
