package com.openat.payment.infrastructure.persistence;

import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.infrastructure.persistence.entity.WalletJpaEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Component
public class WalletRepositoryAdaptor implements WalletRepository {

  private final WalletJpaRepository walletJpaRepository;

  public WalletRepositoryAdaptor(WalletJpaRepository walletJpaRepository) {
    this.walletJpaRepository = walletJpaRepository;
  }

  @Override
  public Wallet save(Wallet wallet) {
    WalletJpaEntity saved = walletJpaRepository.save(WalletJpaEntity.fromDomain(wallet));
    return saved.toDomain();
  }

  @Override
  public Optional<Wallet> findByMemberId(UUID memberId) {
    return walletJpaRepository.findByMemberId(memberId).map(WalletJpaEntity::toDomain);
  }

  @Override
  public Wallet findOrCreateByMemberId(UUID memberId) {
    return walletJpaRepository
        .findByMemberId(memberId)
        .map(WalletJpaEntity::toDomain)
        .orElseGet(() -> insertOrRefetch(memberId));
  }

  private Wallet insertOrRefetch(UUID memberId) {
    try {
      // saveAndFlush 필수 — 충돌을 이 지점에서 즉시 감지(지연 flush면 catch가 못 잡음).
      return walletJpaRepository
          .saveAndFlush(WalletJpaEntity.fromDomain(Wallet.emptyOf(memberId)))
          .toDomain();
    } catch (DataIntegrityViolationException e) {
      // 동시 생성 race에서 패배 — 이긴 쪽 row를 재조회(upsert 패턴). Spring DAO 예외는 infra에 가둔다.
      return walletJpaRepository
          .findByMemberId(memberId)
          .map(WalletJpaEntity::toDomain)
          .orElseThrow(() -> e); // 재조회도 실패하면 원예외로 시끄럽게
    }
  }

  @Override
  public int tryDeduct(UUID id, Long amount) {
    return walletJpaRepository.tryDeduct(id, amount);
  }

  @Override
  public int charge(UUID id, Long amount) {
    return walletJpaRepository.charge(id, amount);
  }
}
