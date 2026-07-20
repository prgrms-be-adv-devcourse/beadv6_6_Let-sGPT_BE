package com.openat.payment.domain.repository;

import com.openat.payment.domain.model.PgReconStatus;
import com.openat.payment.domain.model.Refund;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefundRepository {

    Refund save(Refund refund);

    Optional<Refund> findById(UUID id);

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    // paymentKey(웹훅) → Payment → 이 Payment의 PENDING Refund 역조회용(I1 마이그레이션, plan.md P2).
    // 동시 PENDING이 여러 건일 수 있어(가드 없음, research.md §17.2) List로 반환 — 호출부가 정책을 정한다.
    List<Refund> findByPaymentIdAndStatus(UUID paymentId, Refund.Status status);

    // PG 환불 응답 반영(#10과 동일 원칙) — WHERE status='PENDING' 조건부 UPDATE.
    int tryTransitionFromPending(UUID id, Refund.Status newStatus, String pgRefundKey, LocalDateTime completedAt);

    // TTL 스캐너 — PENDING 상태이고 threshold 이전에 생성된 Refund 조회(토스 환불 응답을 못 받아 굳은 건 회수).
    List<Refund> findStalePending(LocalDateTime threshold);

    // 환불 이력(memberId는 Refund에 없어 paymentId로 Payment를 조인해서 조회, A6) — 0-base page.
    List<Refund> findByMemberId(UUID memberId, int page, int size);

    long countByMemberId(UUID memberId);

    // PG 대사 배치(WS-0) — COMPLETE이면서 아직 MATCHED 아닌 row, 롤링 윈도우로 조회.
    List<Refund> findForPgReconciliation(LocalDateTime from, LocalDateTime to);

    // PG 대사 결과 반영.
    int markPgReconResult(UUID refundId, PgReconStatus pgReconStatus, LocalDateTime reconciledAt);

    // WALLET 환불 완료 직후 즉시 MATCHED 마킹(PG 호출 없음).
    void markPgReconMatched(UUID refundId, LocalDateTime reconciledAt);

    // 정산 대사 일별 API — PG 대사 MATCHED 행만 반환.
    List<Refund> findMatchedCompletedBetween(LocalDateTime from, LocalDateTime to);
}
