package com.openat.settlement.infrastructure.persistence;

import com.openat.settlement.domain.model.RefundReflectedType;
import com.openat.settlement.domain.model.SettlementRefund;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA 전용 정산 환불 Repository입니다.
 */
public interface SettlementRefundJpaRepository extends JpaRepository<SettlementRefund, UUID> {

    Optional<SettlementRefund> findByRefundId(UUID refundId);

    boolean existsByRefundId(UUID refundId);

    @Query("""
            select coalesce(sum(r.refundAmount), 0)
            from SettlementRefund r
            where r.orderId = :orderId
              and r.reflectedType = :reflectedType
            """)
    long sumRefundAmountByOrderIdAndReflectedType(
            @Param("orderId") UUID orderId,
            @Param("reflectedType") RefundReflectedType reflectedType
    );
}
