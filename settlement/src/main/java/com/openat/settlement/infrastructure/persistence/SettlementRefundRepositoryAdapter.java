package com.openat.settlement.infrastructure.persistence;

import com.openat.settlement.domain.model.RefundReflectedType;
import com.openat.settlement.domain.model.SettlementRefund;
import com.openat.settlement.domain.repository.SettlementRefundRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * domain.repository.SettlementRefundRepository 계약을 JPA로 구현하는 Adapter입니다.
 */
@Repository
@RequiredArgsConstructor
public class SettlementRefundRepositoryAdapter implements SettlementRefundRepository {

    private final SettlementRefundJpaRepository jpaRepository;

    @Override
    public Optional<SettlementRefund> findByRefundId(UUID refundId) {
        return jpaRepository.findByRefundId(refundId);
    }

    @Override
    public boolean existsByRefundId(UUID refundId) {
        return jpaRepository.existsByRefundId(refundId);
    }

    @Override
    public long sumRefundAmountByOrderIdAndReflectedType(
            UUID orderId,
            RefundReflectedType reflectedType
    ) {
        return jpaRepository.sumRefundAmountByOrderIdAndReflectedType(orderId, reflectedType);
    }

    @Override
    public SettlementRefund save(SettlementRefund refund) {
        return jpaRepository.save(refund);
    }
}
