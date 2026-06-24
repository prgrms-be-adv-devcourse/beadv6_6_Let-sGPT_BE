package com.openat.settlement.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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
@Table(
        name = "settlement_adjustments",
        indexes = {
                @Index(name = "idx_settlement_adjustments_seller_month", columnList = "seller_id, settlement_month"),
                @Index(name = "idx_settlement_adjustments_status", columnList = "status"),
                @Index(name = "idx_settlement_adjustments_refund_id", columnList = "refund_id")
        }
)
@Comment("정산 후 환불 등 보정 금액 관리")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementAdjustment {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Comment("보정 ID(UUID)")
    @Column(name = "adjustment_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID id;

    @Comment("차감 대상 판매자 ID(UUID)")
    @Column(name = "seller_id", nullable = false, columnDefinition = "uuid")
    private UUID sellerId;

    @Comment("관련 주문 ID(UUID)")
    @Column(name = "order_id", nullable = false, columnDefinition = "uuid")
    private UUID orderId;

    @Comment("정산 후 환불 ID(UUID)")
    @Column(name = "refund_id", nullable = false, columnDefinition = "uuid")
    private UUID refundId;

    @Comment("차감 반영 대상 정산월(YYYYMM)")
    @Column(name = "settlement_month", nullable = false, length = 6)
    private String settlementMonth;

    @Enumerated(EnumType.STRING)
    @Comment("보정 유형(POST_REFUND)")
    @Column(name = "adjustment_type", nullable = false, length = 30)
    private AdjustmentType adjustmentType;

    @Comment("보정 금액, 차감 처리를 위해 음수 금액 저장")
    @Column(name = "adjustment_amount", nullable = false, columnDefinition = "BIGINT")
    private Long adjustmentAmount;

    @Enumerated(EnumType.STRING)
    @Comment("보정 상태(READY, APPLIED)")
    @Column(name = "status", nullable = false, length = 20)
    private AdjustmentStatus status;

    @Comment("보정 사유")
    @Column(name = "reason", length = 500)
    private String reason;

    @Comment("생성 일시")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static SettlementAdjustment createPostRefund(
            UUID sellerId,
            UUID orderId,
            UUID refundId,
            String settlementMonth,
            long refundAmount,
            String reason
    ) {
        if (refundAmount <= 0) {
            throw new IllegalArgumentException("환불금액은 0보다 커야 합니다.");
        }

        SettlementAdjustment adjustment = new SettlementAdjustment();
        adjustment.sellerId = sellerId;
        adjustment.orderId = orderId;
        adjustment.refundId = refundId;
        adjustment.settlementMonth = settlementMonth;
        adjustment.adjustmentType = AdjustmentType.POST_REFUND;
        adjustment.adjustmentAmount = -Math.abs(refundAmount);
        adjustment.status = AdjustmentStatus.READY;
        adjustment.reason = reason;
        return adjustment;
    }

    public void apply() {
        this.status = AdjustmentStatus.APPLIED;
    }

    public boolean isReady() {
        return this.status == AdjustmentStatus.READY;
    }

    public boolean isApplied() {
        return this.status == AdjustmentStatus.APPLIED;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();

        if (this.status == null) {
            this.status = AdjustmentStatus.READY;
        }
    }
}
