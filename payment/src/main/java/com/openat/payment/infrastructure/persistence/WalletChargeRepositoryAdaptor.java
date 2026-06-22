package com.openat.payment.infrastructure.persistence;

import com.openat.payment.domain.model.WalletCharge;
import com.openat.payment.domain.repository.WalletChargeRepository;
import com.openat.payment.infrastructure.persistence.entity.WalletChargeJpaEntity;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class WalletChargeRepositoryAdaptor implements WalletChargeRepository {

    private final WalletChargeJpaRepository walletChargeJpaRepository;

    public WalletChargeRepositoryAdaptor(WalletChargeJpaRepository walletChargeJpaRepository) {
        this.walletChargeJpaRepository = walletChargeJpaRepository;
    }

    @Override
    public WalletCharge save(WalletCharge charge) {
        WalletChargeJpaEntity saved = walletChargeJpaRepository.save(WalletChargeJpaEntity.fromDomain(charge));
        return saved.toDomain();
    }

    @Override
    public Optional<WalletCharge> findByIdempotencyKey(String idempotencyKey) {
        return walletChargeJpaRepository.findByIdempotencyKey(idempotencyKey).map(WalletChargeJpaEntity::toDomain);
    }
}
