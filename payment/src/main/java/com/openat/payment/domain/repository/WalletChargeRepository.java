package com.openat.payment.domain.repository;

import com.openat.payment.domain.model.WalletCharge;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletChargeRepository {

    WalletCharge save(WalletCharge charge);

    Optional<WalletCharge> findById(UUID id);

    Optional<WalletCharge> findByIdempotencyKey(String idempotencyKey);

    Optional<WalletCharge> findByPgPaymentKey(String pgPaymentKey);

    // confirm 예약(TX1, 신-하자드9와 동일 원칙) — PG 호출 전에 pgPaymentKey를 기록하며 WHERE status='PENDING'
    // 조건부 UPDATE로 선점한다. affected=0이면 그 사이 다른 경로(보조 웹훅/재시도)가 이미 확정한 것.
    int reservePgPaymentKeyIfPending(UUID id, String pgPaymentKey);

    // 웹훅/confirm 처리(#10과 동일 원칙) — WHERE status='PENDING' 조건부 UPDATE, affected rows=0이면 이미 처리됐거나 대상 없음.
    int tryTransitionFromPending(UUID id, WalletCharge.Status newStatus, String pgTxId);

    // TTL 스캐너(§5 하자드#10) — PENDING 상태이고 threshold 이전에 생성된 row 조회.
    List<WalletCharge> findStalePending(LocalDateTime threshold);
}
