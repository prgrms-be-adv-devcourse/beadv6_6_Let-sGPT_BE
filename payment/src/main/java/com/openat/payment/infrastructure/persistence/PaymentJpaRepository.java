package com.openat.payment.infrastructure.persistence;

import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.PgReconStatus;
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

    // PG 대사 배치(WS-0) — APPROVED이면서 아직 MATCHED 확정 안 된(NOT_CHECKED/MISMATCH) row.
    // approvedAt 범위는 롤링 윈도우(오늘 배치 대상일 + 과거 미해소분 재시도)로 호출측이 계산해서 넘긴다.
    List<PaymentJpaEntity> findByStatusAndPgReconStatusNotAndApprovedAtBetween(
            Payment.Status status, PgReconStatus pgReconStatus, LocalDateTime from, LocalDateTime to);

    // PG 대사 결과 반영 — pgReconStatus/pgReconciledAt만 갱신. flushAutomatically=true 필수 — 실기동 검증으로 확인:
    // PgReconciliationService가 이 UPDATE 직전에 discrepancyRepository.save()로 불일치 행을 저장하는데, 자동
    // flush 없이는 그 INSERT가 아직 DB에 반영 안 된 채로 이 벌크 UPDATE가 실행되고, clearAutomatically가 영속성
    // 컨텍스트를 비우면서 flush 안 된 그 INSERT가 유실된다(RefundJpaRepository.tryTransitionFromPending과 동일 원인).
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PaymentJpaEntity p SET p.pgReconStatus = :pgReconStatus, p.pgReconciledAt = :reconciledAt "
            + "WHERE p.id = :id")
    int markPgReconResult(@Param("id") UUID id, @Param("pgReconStatus") PgReconStatus pgReconStatus,
            @Param("reconciledAt") LocalDateTime reconciledAt);

    // 정산 대사 일별 API(reconciliation.md) — PG 대사 MATCHED 행만 정산에 노출(WS-0.3).
    List<PaymentJpaEntity> findByStatusAndApprovedAtGreaterThanEqualAndApprovedAtLessThanAndPgReconStatus(
            Payment.Status status, LocalDateTime from, LocalDateTime to, PgReconStatus pgReconStatus);

    // 환불 조립용 배치 조회(N+1 회피) — refunds의 paymentId 집합을 한 번에 조회.
    List<PaymentJpaEntity> findByIdIn(List<UUID> ids);
}
