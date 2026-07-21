package com.openat.payment.infrastructure.persistence;

import com.openat.payment.domain.model.PgReconStatus;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.infrastructure.persistence.entity.RefundJpaEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundJpaRepository extends JpaRepository<RefundJpaEntity, UUID> {

    Optional<RefundJpaEntity> findByIdempotencyKey(String idempotencyKey);

    // paymentKey(웹훅) → Payment → PENDING Refund 역조회용(plan.md P2).
    List<RefundJpaEntity> findByPaymentIdAndStatus(UUID paymentId, Refund.Status status);

    // PG 환불 응답/웹훅 처리(#10과 동일 원칙) — WHERE status='PENDING' 조건부 UPDATE.
    // flushAutomatically=true 필수 — RefundService.requestRefund()는 save()로 PENDING row를 만든 직후
    // 같은 트랜잭션 안에서 이 벌크 UPDATE를 실행한다. 자동 flush 없이는 아직 DB에 반영 안 된 INSERT를
    // 이 UPDATE가 못 보고 0 rows affected로 끝나며, 그 뒤 clearAutomatically가 영속성 컨텍스트를 비우면서
    // 아직 flush 안 된 그 INSERT 자체가 유실된다(웹훅 핸들러처럼 "이전 트랜잭션에서 이미 커밋된 row를
    // 조회 후 갱신"하는 다른 호출부와 달리, 이 메서드만 같은 트랜잭션 내 save 직후 호출되는 경로라 필요).
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefundJpaEntity r SET r.status = :newStatus, r.pgRefundKey = :pgRefundKey, r.completedAt = :completedAt "
            + "WHERE r.id = :id AND r.status = 'PENDING'")
    int tryTransitionFromPending(@Param("id") UUID id, @Param("newStatus") Refund.Status newStatus,
            @Param("pgRefundKey") String pgRefundKey, @Param("completedAt") LocalDateTime completedAt);

    // TTL 스캐너 — PENDING 상태이고 threshold 이전에 생성된 Refund 조회.
    @Query("SELECT r FROM RefundJpaEntity r WHERE r.status = 'PENDING' AND r.createdAt < :threshold")
    List<RefundJpaEntity> findStalePending(@Param("threshold") LocalDateTime threshold);

    // Refund에는 memberId가 없어(A6) paymentId로 Payment를 서브쿼리 조인해서 조회.
    @Query("SELECT r FROM RefundJpaEntity r WHERE r.paymentId IN "
            + "(SELECT p.id FROM PaymentJpaEntity p WHERE p.memberId = :memberId) ORDER BY r.createdAt DESC")
    List<RefundJpaEntity> findByMemberId(@Param("memberId") UUID memberId, Pageable pageable);

    @Query("SELECT COUNT(r) FROM RefundJpaEntity r WHERE r.paymentId IN "
            + "(SELECT p.id FROM PaymentJpaEntity p WHERE p.memberId = :memberId)")
    long countByMemberId(@Param("memberId") UUID memberId);

    // PG 대사 배치(WS-0) — COMPLETE이면서 아직 MATCHED 확정 안 된 row.
    List<RefundJpaEntity> findByStatusAndPgReconStatusNotAndCompletedAtBetween(
            Refund.Status status, PgReconStatus pgReconStatus, LocalDateTime from, LocalDateTime to);

    // PG 대사 결과 반영. flushAutomatically=true 필수 — PgReconciliationService가 이 UPDATE 직전에
    // discrepancyRepository.save()로 불일치 행을 저장하는데, 자동 flush 없이는 그 INSERT가 유실된다
    // (PaymentJpaRepository.markPgReconResult/tryTransitionFromPending과 동일 원인, 실기동 검증으로 확인).
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefundJpaEntity r SET r.pgReconStatus = :pgReconStatus, r.pgReconciledAt = :reconciledAt "
            + "WHERE r.id = :id")
    int markPgReconResult(@Param("id") UUID id, @Param("pgReconStatus") PgReconStatus pgReconStatus,
            @Param("reconciledAt") LocalDateTime reconciledAt);

    // WALLET 환불 완료 직후 즉시 MATCHED 마킹(PG 호출 없음, RefundService.creditWallet 이후).
    @Modifying(clearAutomatically = true)
    @Query("UPDATE RefundJpaEntity r SET r.pgReconStatus = 'MATCHED', r.pgReconciledAt = :reconciledAt "
            + "WHERE r.id = :id")
    int markPgReconMatched(@Param("id") UUID id, @Param("reconciledAt") LocalDateTime reconciledAt);

    // 정산 대사 일별 API — PG 대사 MATCHED 행만 정산에 노출(WS-0.3).
    List<RefundJpaEntity> findByStatusAndCompletedAtGreaterThanEqualAndCompletedAtLessThanAndPgReconStatus(
            Refund.Status status, LocalDateTime from, LocalDateTime to, PgReconStatus pgReconStatus);
}
