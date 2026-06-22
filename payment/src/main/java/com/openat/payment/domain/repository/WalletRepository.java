package com.openat.payment.domain.repository;

import com.openat.payment.domain.model.Wallet;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository {

    Wallet save(Wallet wallet);

    Optional<Wallet> findByMemberId(UUID memberId);

    // 잔액 차감(#8) — affected rows 0이면 잔액부족, 단일 조건부 UPDATE로 원자처리.
    int tryDeduct(UUID id, Long amount);

    int charge(UUID id, Long amount);
}
