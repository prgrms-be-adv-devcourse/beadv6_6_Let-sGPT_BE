package com.openat.settlement.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
        name = "settlement_refunds",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_settlement_refund_refund_id",
                        columnNames = "refund_id"
                )
        },
        indexes = {
                @Index(name = "idx_settlement_refund_order_id", columnList = "order_id"),
                @Index(name = "idx_settlement_refund_seller_id", columnList = "seller_id"),
                @Index(name = "idx_settlement_refund_payment_id", columnList = "payment_id")
        }
)
@Comment("환불 완료 건의 정산 차감 관리")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementRefund {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Comment("정산 환불 ID(UUID)")
    @Column(name = "settlement_refund_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID id;

    @Comment("환불 ID(UUID), 중복 저장 방지")
    @Column(name = "refund_id", nullable = false, columnDefinition = "uuid")
    private UUID refundId;

    @Comment("결제 ID(UUID)")
    @Column(name = "payment_id", nullable = false, columnDefinition = "uuid")
    private UUID paymentId;

    @Comment("주문 ID(UUID)")
    @Column(name = "order_id", nullable = false, columnDefinition = "uuid")
    private UUID orderId;

    @Comment("판매자 ID(UUID)")
    @Column(name = "seller_id", nullable = false, columnDefinition = "uuid")
    private UUID sellerId;

    @Comment("구매자 ID(UUID)")
    @Column(name = "buyer_id", nullable = false, columnDefinition = "uuid")
    private UUID buyerId;

    @Comment("환불 금액")
    @Column(name = "refund_amount", nullable = false, columnDefinition = "BIGINT")
    private Long refundAmount;

    @Comment("환불 사유")
    @Column(name = "refund_reason", length = 100)
    private String refundReason;

    @Enumerated(EnumType.STRING)
    @Comment("환불 상태(COMPLETED만 저장)")
    @Column(name = "refund_status", nullable = false, length = 20)
    private RefundStatus refundStatus;

    @Enumerated(EnumType.STRING)
    @Comment("정산 반영 유형(BEFORE_SETTLEMENT, AFTER_SETTLEMENT)")
    @Column(name = "reflected_type", nullable = false, length = 30)
    private RefundReflectedType reflectedType;

    @Comment("환불 완료 일시")
    @Column(name = "refunded_at", nullable = false)
    private LocalDateTime refundedAt;

    @Comment("정산 서비스 저장 일시")
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static SettlementRefund create(
            UUID refundId,
            UUID paymentId,
            UUID orderId,
            UUID sellerId,
            UUID buyerId,
            long refundAmount,
            String refundReason,
            RefundReflectedType reflectedType,
            LocalDateTime refundedAt
    ) {
        if (refundAmount <= 0) {
            throw new IllegalArgumentException("환불금액은 0보다 커야 합니다.");
        }

        SettlementRefund refund = new SettlementRefund();
        refund.refundId = refundId;
        refund.paymentId = paymentId;
        refund.orderId = orderId;
        refund.sellerId = sellerId;
        refund.buyerId = buyerId;
        refund.refundAmount = refundAmount;
        refund.refundReason = refundReason;
        refund.refundStatus = RefundStatus.COMPLETED;
        refund.reflectedType = reflectedType;
        refund.refundedAt = refundedAt;
        return refund;
    }

    public boolean isBeforeSettlement() {
        return this.reflectedType == RefundReflectedType.BEFORE_SETTLEMENT;
    }

    public boolean isAfterSettlement() {
        return this.reflectedType == RefundReflectedType.AFTER_SETTLEMENT;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();

        if (this.refundStatus == null) {
            this.refundStatus = RefundStatus.COMPLETED;
        }
    }
}
