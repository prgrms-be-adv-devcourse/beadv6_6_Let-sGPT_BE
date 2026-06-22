package com.openat.payment.infrastructure.persistence;

import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.infrastructure.persistence.entity.WalletJpaEntity;
import java.util.Optional;
import java.util.UUID;
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
    public int tryDeduct(UUID id, Long amount) {
        return walletJpaRepository.tryDeduct(id, amount);
    }

    @Override
    public int charge(UUID id, Long amount) {
        return walletJpaRepository.charge(id, amount);
    }
}
