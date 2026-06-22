package com.openat.payment.domain.repository;

import com.openat.payment.domain.model.WalletCharge;
import java.util.Optional;
import java.util.UUID;

public interface WalletChargeRepository {

    WalletCharge save(WalletCharge charge);

    Optional<WalletCharge> findById(UUID id);

    Optional<WalletCharge> findByIdempotencyKey(String idempotencyKey);

    Optional<WalletCharge> findByPgPaymentKey(String pgPaymentKey);

    // confirm 요청으로 전달받은 값 반영(신-하자드9와 동일 원칙) — 이 시점엔 해당 row를 우리만 갖고 있어 조건부 UPDATE 불필요.
    void updatePgPaymentKey(UUID id, String pgPaymentKey);

    // 웹훅/confirm 처리(#10과 동일 원칙) — WHERE status='PENDING' 조건부 UPDATE, affected rows=0이면 이미 처리됐거나 대상 없음.
    int tryTransitionFromPending(UUID id, WalletCharge.Status newStatus, String pgTxId);
}
