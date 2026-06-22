package com.openat.payment.domain.repository;

import com.openat.payment.domain.model.Payment;
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

    // order_completed 사후채움(#14) — affected rows 반환(0이면 대상 없음/이미 채움, 멱등).
    int tryFillSellerAndProduct(UUID orderId, UUID sellerId, UUID productId);

    // 웹훅 처리(#10) — WHERE status='PAYMENT_PENDING' 조건부 UPDATE, affected rows=0이면 이미 처리됐거나 대상 없음.
    int tryTransitionFromPending(UUID id, Payment.Status newStatus, String pgTxId, LocalDateTime approvedAt);

    // PG confirm 요청으로 전달받은 값 반영(A16) — 이 시점엔 해당 row를 우리만 갖고 있어 조건부 UPDATE 불필요.
    void updatePgPaymentKey(UUID id, String pgPaymentKey);

    // TTL 스캐너(§3) — 생성 후 threshold 이전인 PAYMENT_PENDING row 전체(pgPaymentKey null/有 둘 다 포함, 분기는 호출 측이 처리).
    List<Payment> findStalePending(LocalDateTime threshold);
}
