package com.openat.payment.infrastructure.persistence;

import com.openat.payment.domain.model.Payment;
import com.openat.payment.infrastructure.persistence.entity.PaymentJpaEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, UUID> {

    Optional<PaymentJpaEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentJpaEntity> findFirstByOrderIdAndStatusOrderByCreatedAtDesc(UUID orderId, Payment.Status status);

    Optional<PaymentJpaEntity> findByPgPaymentKeyHash(String pgPaymentKeyHash);

    Optional<PaymentJpaEntity> findByOrderId(UUID orderId);

    // order_completed 이벤트로 sellerId/productId 사후채움(#14) — 이미 채워져 있으면 0행(멱등).
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PaymentJpaEntity p SET p.sellerId = :sellerId, p.productId = :productId "
            + "WHERE p.orderId = :orderId AND p.status = 'APPROVED' AND p.sellerId IS NULL")
    int tryFillSellerAndProduct(@Param("orderId") UUID orderId, @Param("sellerId") UUID sellerId,
            @Param("productId") UUID productId);

    // PG 웹훅 처리(#10) — WHERE status='PAYMENT_PENDING' 조건부 UPDATE, affected rows=0이면 이미 처리됐거나 대상 없음.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PaymentJpaEntity p SET p.status = :newStatus, p.pgTxId = :pgTxId, p.approvedAt = :approvedAt "
            + "WHERE p.id = :id AND p.status = 'PAYMENT_PENDING'")
    int tryTransitionFromPending(@Param("id") UUID id, @Param("newStatus") Payment.Status newStatus,
            @Param("pgTxId") String pgTxId, @Param("approvedAt") LocalDateTime approvedAt);

    // TTL 스캐너(§3) — 생성 후 threshold 이전인 PAYMENT_PENDING row 전체.
    List<PaymentJpaEntity> findByStatusAndCreatedAtBefore(Payment.Status status, LocalDateTime threshold);

    // 환불가능액 원자 검증(#13) — 합계가 원 결제금액을 넘지 않을 때만 증가.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PaymentJpaEntity p SET p.refundedAmount = p.refundedAmount + :amount "
            + "WHERE p.id = :id AND p.refundedAmount + :amount <= p.amount")
    int tryIncreaseRefundedAmount(@Param("id") UUID id, @Param("amount") Long amount);

    // PG가 환불을 명시적으로 거절했을 때 한도 원복.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PaymentJpaEntity p SET p.refundedAmount = p.refundedAmount - :amount "
            + "WHERE p.id = :id AND p.refundedAmount - :amount >= 0")
    int tryDecreaseRefundedAmount(@Param("id") UUID id, @Param("amount") Long amount);
}
