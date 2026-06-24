package com.openat.settlement.application.service;

import com.openat.settlement.domain.model.RefundReflectedType;
import com.openat.settlement.domain.model.SettlementAdjustment;
import com.openat.settlement.domain.model.SettlementOrder;
import com.openat.settlement.domain.model.SettlementRefund;
import com.openat.settlement.domain.repository.SettlementAdjustmentRepository;
import com.openat.settlement.domain.repository.SettlementOrderRepository;
import com.openat.settlement.domain.repository.SettlementRefundRepository;
import com.openat.settlement.infrastructure.kafka.event.PaymentCompletedEvent;
import com.openat.settlement.infrastructure.kafka.event.PaymentRefundedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment Kafka 이벤트를 정산 재료 테이블에 반영하는 Application Service입니다.
 */
@Service
@RequiredArgsConstructor
public class PaymentSettlementEventService {

    private final SettlementOrderRepository settlementOrderRepository;
    private final SettlementRefundRepository settlementRefundRepository;
    private final SettlementAdjustmentRepository settlementAdjustmentRepository;

    /**
     * PAYMENT_COMPLETED 수신 시 orderId 기준으로 settlement_orders를 UPSERT합니다.
     * 환불 이벤트가 먼저 들어온 경우를 대비해서 기존 BEFORE_SETTLEMENT 환불 총합도 재반영합니다.
     */
    @Transactional
    public void upsertSettlementOrder(PaymentCompletedEvent event) {
        SettlementOrder settlementOrder = settlementOrderRepository.findByOrderId(event.orderId())
                .map(existingOrder -> {
                    if (existingOrder.isCompleted()) {
                        return existingOrder;
                    }

                    existingOrder.updateFromPaymentCompleted(
                            event.paymentId(),
                            event.sellerId(),
                            event.buyerId(),
                            event.productId(),
                            event.settlementMonth(),
                            event.orderAmount(),
                            event.paidAmount(),
                            event.feeAmount(),
                            event.paidAt()
                    );
                    return existingOrder;
                })
                .orElseGet(() -> SettlementOrder.create(
                        event.paymentId(),
                        event.orderId(),
                        event.sellerId(),
                        event.buyerId(),
                        event.productId(),
                        event.settlementMonth(),
                        event.orderAmount(),
                        event.paidAmount(),
                        event.feeAmount(),
                        event.paidAt()
                ));

        if (settlementOrder.isCompleted()) {
            return;
        }

        long totalBeforeSettlementRefundAmount =
                settlementRefundRepository.sumRefundAmountByOrderIdAndReflectedType(
                        event.orderId(),
                        RefundReflectedType.BEFORE_SETTLEMENT
                );

        settlementOrder.applyTotalRefundBeforeSettlement(totalBeforeSettlementRefundAmount);
        settlementOrderRepository.save(settlementOrder);
    }

    /**
     * PAYMENT_REFUNDED 수신 시 refundId 기준으로 중복을 방지하고 settlement_refunds에 저장합니다.
     * 정산 전 주문이면 settlement_orders 환불 누적 금액을 총합 기준으로 재계산합니다.
     * 정산 완료 후 환불이면 settlement_adjustments에 차감 보정 데이터를 생성합니다.
     */
    @Transactional
    public void saveSettlementRefund(PaymentRefundedEvent event) {
        if (settlementRefundRepository.existsByRefundId(event.refundId())) {
            return;
        }

        SettlementOrder settlementOrder = settlementOrderRepository.findByOrderId(event.orderId())
                .orElse(null);

        RefundReflectedType reflectedType = determineReflectedType(settlementOrder);

        SettlementRefund settlementRefund = SettlementRefund.create(
                event.refundId(),
                event.paymentId(),
                event.orderId(),
                event.sellerId(),
                event.buyerId(),
                event.refundAmount(),
                event.refundReason(),
                reflectedType,
                event.refundedAt()
        );

        settlementRefundRepository.save(settlementRefund);

        if (settlementOrder != null && settlementOrder.isReady()) {
            long totalBeforeSettlementRefundAmount =
                    settlementRefundRepository.sumRefundAmountByOrderIdAndReflectedType(
                            event.orderId(),
                            RefundReflectedType.BEFORE_SETTLEMENT
                    );

            settlementOrder.applyTotalRefundBeforeSettlement(totalBeforeSettlementRefundAmount);
            settlementOrderRepository.save(settlementOrder);
            return;
        }

        if (reflectedType == RefundReflectedType.AFTER_SETTLEMENT) {
            createPostRefundAdjustment(event);
        }
    }

    private RefundReflectedType determineReflectedType(SettlementOrder settlementOrder) {
        if (settlementOrder == null) {
            return RefundReflectedType.BEFORE_SETTLEMENT;
        }

        if (settlementOrder.isCompleted()) {
            return RefundReflectedType.AFTER_SETTLEMENT;
        }

        return RefundReflectedType.BEFORE_SETTLEMENT;
    }

    private void createPostRefundAdjustment(PaymentRefundedEvent event) {
        if (settlementAdjustmentRepository.existsByRefundId(event.refundId())) {
            return;
        }

        SettlementAdjustment adjustment = SettlementAdjustment.createPostRefund(
                event.sellerId(),
                event.orderId(),
                event.refundId(),
                event.settlementMonth(),
                event.refundAmount(),
                event.refundReason()
        );

        settlementAdjustmentRepository.save(adjustment);
    }
}
