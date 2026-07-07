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
        name = "settlement_orders",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_settlement_order_order_id",
                        columnNames = "order_id"
                )
        },
        indexes = {
                @Index(name = "idx_settlement_order_seller_month", columnList = "seller_id, settlement_month"),
                @Index(name = "idx_settlement_order_status", columnList = "settlement_status"),
                @Index(name = "idx_settlement_order_payment_id", columnList = "payment_id")
        }
)
@Comment("주문/결제 기준 정산 재료 관리")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementOrder {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.TIME)
    @Comment("정산 주문 ID(UUID)")
    @Column(name = "settlement_order_id", nullable = false, updatable = false, columnDefinition = "uuid")
    private UUID id;

    @Comment("판매자 정산 결과 ID(UUID), 정산 완료 후 설정")
    @Column(name = "seller_settlement_id", columnDefinition = "uuid")
    private UUID sellerSettlementId;

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

    @Comment("상품 ID(UUID)")
    @Column(name = "product_id", nullable = false, columnDefinition = "uuid")
    private UUID productId;

    @Comment("정산월(YYYYMM)")
    @Column(name = "settlement_month", nullable = false, length = 6)
    private String settlementMonth;

    @Comment("주문 금액")
    @Column(name = "order_amount", nullable = false, columnDefinition = "BIGINT")
    private Long orderAmount;

    @Comment("결제 완료 금액")
    @Column(name = "paid_amount", nullable = false, columnDefinition = "BIGINT")
    private Long paidAmount;

    @Comment("플랫폼 수수료")
    @Column(name = "fee_amount", nullable = false, columnDefinition = "BIGINT")
    private Long feeAmount;

    @Comment("정산 전 환불 반영 누적 금액")
    @Column(name = "refund_amount", nullable = false, columnDefinition = "BIGINT")
    private Long refundAmount;

    @Comment("순 정산 금액 = 결제완료금액 - 수수료 - 환불금액")
    @Column(name = "net_settlement_amount", nullable = false, columnDefinition = "BIGINT")
    private Long netSettlementAmount;

    @Enumerated(EnumType.STRING)
    @Comment("정산 상태(READY, COMPLETED)")
    @Column(name = "settlement_status", nullable = false, length = 20)
    private SettlementOrderStatus settlementStatus;

    @Comment("결제 완료 일시")
    @Column(name = "paid_at", nullable = false)
    private LocalDateTime paidAt;

    public static SettlementOrder create(
            UUID paymentId,
            UUID orderId,
            UUID sellerId,
            UUID buyerId,
            UUID productId,
            String settlementMonth,
            long orderAmount,
            long paidAmount,
            long feeAmount,
            LocalDateTime paidAt
    ) {
        SettlementOrder order = new SettlementOrder();
        order.paymentId = paymentId;
        order.orderId = orderId;
        order.sellerId = sellerId;
        order.buyerId = buyerId;
        order.productId = productId;
        order.settlementMonth = settlementMonth;
        order.orderAmount = orderAmount;
        order.paidAmount = paidAmount;
        order.feeAmount = feeAmount;
        order.refundAmount = 0L;
        order.netSettlementAmount = paidAmount - feeAmount;
        order.settlementStatus = SettlementOrderStatus.READY;
        order.paidAt = paidAt;
        return order;
    }

    public void reflectRefundBeforeSettlement(long refundAmount) {
        validatePositiveAmount(refundAmount);

        if (this.settlementStatus == SettlementOrderStatus.COMPLETED) {
            throw new IllegalStateException("이미 정산 완료된 주문은 정산주문 환불금액에 직접 반영할 수 없습니다.");
        }

        this.refundAmount += refundAmount;
        recalculateNetSettlementAmount();
    }

    /**
     * 결제 완료 Kafka 이벤트를 다시 받아도 orderId 기준 UPSERT가 가능하도록
     * 정산 완료 전 데이터만 갱신합니다.
     */
    public void updateFromPaymentCompleted(
            UUID paymentId,
            UUID sellerId,
            UUID buyerId,
            UUID productId,
            String settlementMonth,
            long orderAmount,
            long paidAmount,
            long feeAmount,
            LocalDateTime paidAt
    ) {
        if (this.settlementStatus == SettlementOrderStatus.COMPLETED) {
            throw new IllegalStateException("이미 정산 완료된 주문은 결제 완료 이벤트로 수정할 수 없습니다.");
        }

        this.paymentId = paymentId;
        this.sellerId = sellerId;
        this.buyerId = buyerId;
        this.productId = productId;
        this.settlementMonth = settlementMonth;
        this.orderAmount = orderAmount;
        this.paidAmount = paidAmount;
        this.feeAmount = feeAmount;
        this.paidAt = paidAt;

        recalculateNetSettlementAmount();
    }

    /**
     * Kafka 이벤트 재처리로 환불금액이 중복 증가하지 않도록
     * 환불 누적액을 더하기가 아니라 총합 기준으로 반영합니다.
     */
    public void applyTotalRefundBeforeSettlement(long totalRefundAmount) {
        if (totalRefundAmount < 0) {
            throw new IllegalArgumentException("환불 누적 금액은 0보다 작을 수 없습니다.");
        }

        if (this.settlementStatus == SettlementOrderStatus.COMPLETED) {
            throw new IllegalStateException("이미 정산 완료된 주문은 환불 누적 금액을 수정할 수 없습니다.");
        }

        this.refundAmount = totalRefundAmount;
        recalculateNetSettlementAmount();
    }

    public void complete(UUID sellerSettlementId) {
        if (sellerSettlementId == null) {
            throw new IllegalArgumentException("sellerSettlementId는 필수입니다.");
        }

        this.sellerSettlementId = sellerSettlementId;
        this.settlementStatus = SettlementOrderStatus.COMPLETED;
    }

    public boolean isReady() {
        return this.settlementStatus == SettlementOrderStatus.READY;
    }

    public boolean isCompleted() {
        return this.settlementStatus == SettlementOrderStatus.COMPLETED;
    }

    private void recalculateNetSettlementAmount() {
        this.netSettlementAmount = this.paidAmount - this.feeAmount - this.refundAmount;
    }

    private void validatePositiveAmount(long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("금액은 0보다 커야 합니다.");
        }
    }

    @PrePersist
    protected void onCreate() {
        if (this.refundAmount == null) {
            this.refundAmount = 0L;
        }

        if (this.netSettlementAmount == null) {
            this.netSettlementAmount = this.paidAmount - this.feeAmount - this.refundAmount;
        }

        if (this.settlementStatus == null) {
            this.settlementStatus = SettlementOrderStatus.READY;
        }
    }
}
