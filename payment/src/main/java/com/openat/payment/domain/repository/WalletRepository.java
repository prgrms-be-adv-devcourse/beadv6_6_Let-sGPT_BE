package com.openat.payment.domain.repository;

import com.openat.payment.domain.model.Wallet;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository {

  Wallet save(Wallet wallet);

  Optional<Wallet> findByMemberId(UUID memberId);

  // find→save race(§4.4, 7-12 plan WS-F)는 member_id UNIQUE 제약(V1 DDL)으로 500이 나던 것 —
  // 충돌을 어댑터에서 흡수(재조회)해 호출부는 항상 지갑을 받는다.
  Wallet findOrCreateByMemberId(UUID memberId);

  // 잔액 차감(#8) — affected rows 0이면 잔액부족, 단일 조건부 UPDATE로 원자처리.
  int tryDeduct(UUID id, Long amount);

  int charge(UUID id, Long amount);
}
