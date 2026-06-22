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

    Optional<PaymentJpaEntity> findByOrderIdAndStatus(UUID orderId, Payment.Status status);

    Optional<PaymentJpaEntity> findByPgPaymentKeyHash(String pgPaymentKeyHash);

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

    // PG 토큰발급 응답 반영(#9) — 이 시점엔 해당 row를 우리만 갖고 있어 조건부 UPDATE 불필요.
    // pgPaymentKey는 암호화 컬럼이라 등호조회가 안 되므로, 평문 해시(pgPaymentKeyHash)를 같이 저장해 그걸로 조회.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE PaymentJpaEntity p SET p.pgPaymentKey = :pgPaymentKey, p.pgPaymentKeyHash = :pgPaymentKeyHash "
            + "WHERE p.id = :id")
    void updatePgPaymentKey(@Param("id") UUID id, @Param("pgPaymentKey") String pgPaymentKey,
            @Param("pgPaymentKeyHash") String pgPaymentKeyHash);

    // TTL 스캐너(§3) — 생성 후 threshold 이전인 PAYMENT_PENDING row 전체.
    List<PaymentJpaEntity> findByStatusAndCreatedAtBefore(Payment.Status status, LocalDateTime threshold);
}
