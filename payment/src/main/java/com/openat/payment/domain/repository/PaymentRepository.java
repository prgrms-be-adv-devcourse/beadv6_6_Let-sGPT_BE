package com.openat.payment.domain.repository;

import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.PgReconStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {

    Payment save(Payment payment);

    Optional<Payment> findById(UUID id);

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByOrderIdAndStatus(UUID orderId, Payment.Status status);

    Optional<Payment> findByPgPaymentKey(String pgPaymentKey);

    // confirm 단일 진입점(7-13 plan WS-B) — order_id 유니크 예약 INSERT. 충돌 시 빈 Optional(호출측이
    // findByOrderId로 기존 행을 재조회해 4갈래 분기).
    Optional<Payment> tryReserveForConfirm(Payment pending);

    Optional<Payment> findByOrderId(UUID orderId);

    // order_completed 사후채움(#14) — affected rows 반환(0이면 대상 없음/이미 채움, 멱등).
    int tryFillSellerAndProduct(UUID orderId, UUID sellerId, UUID productId);

    // 웹훅 처리(#10) — WHERE status='PAYMENT_PENDING' 조건부 UPDATE, affected rows=0이면 이미 처리됐거나 대상 없음.
    int tryTransitionFromPending(UUID id, Payment.Status newStatus, String pgTxId, LocalDateTime approvedAt);

    // TTL 스캐너(§3) — 생성 후 threshold 이전인 PAYMENT_PENDING row 전체(pgPaymentKey null/有 둘 다 포함, 분기는 호출 측이 처리).
    List<Payment> findStalePending(LocalDateTime threshold);

    // 환불가능액 원자 검증(#13) — WHERE refundedAmount + amount <= amount, affected=0이면 한도초과.
    int tryIncreaseRefundedAmount(UUID paymentId, Long amount);

    // PG가 환불을 명시적으로 거절했을 때 한도를 원복(보정용).
    int tryDecreaseRefundedAmount(UUID paymentId, Long amount);

    // PG 대사 배치(WS-0) — APPROVED이면서 아직 MATCHED 아닌 row(NOT_CHECKED/MISMATCH), 롤링 윈도우로 조회.
    List<Payment> findForPgReconciliation(LocalDateTime from, LocalDateTime to);

    // PG 대사 결과 반영.
    int markPgReconResult(UUID paymentId, PgReconStatus pgReconStatus, LocalDateTime reconciledAt);

    // 정산 대사 일별 API(reconciliation.md) — PG 대사 MATCHED 행만 반환.
    List<Payment> findMatchedApprovedBetween(LocalDateTime from, LocalDateTime to);

    // 환불 조립용 배치 조회(N+1 회피).
    List<Payment> findAllByIds(List<UUID> ids);
}
