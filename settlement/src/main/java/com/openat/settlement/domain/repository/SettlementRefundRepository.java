package com.openat.settlement.domain.repository;

import com.openat.settlement.domain.model.RefundReflectedType;
import com.openat.settlement.domain.model.SettlementRefund;

import java.util.Optional;
import java.util.UUID;

/**
 * 정산 환불 저장소 계약입니다.
 */
public interface SettlementRefundRepository {

    Optional<SettlementRefund> findByRefundId(UUID refundId);

    boolean existsByRefundId(UUID refundId);

    long sumRefundAmountByOrderIdAndReflectedType(
            UUID orderId,
            RefundReflectedType reflectedType
    );

    SettlementRefund save(SettlementRefund refund);
}
