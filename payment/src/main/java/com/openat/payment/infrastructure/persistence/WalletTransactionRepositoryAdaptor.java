package com.openat.payment.infrastructure.persistence;

import com.openat.payment.domain.model.WalletTransaction;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import com.openat.payment.infrastructure.persistence.entity.WalletTransactionJpaEntity;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class WalletTransactionRepositoryAdaptor implements WalletTransactionRepository {

    private final WalletTransactionJpaRepository walletTransactionJpaRepository;

    public WalletTransactionRepositoryAdaptor(WalletTransactionJpaRepository walletTransactionJpaRepository) {
        this.walletTransactionJpaRepository = walletTransactionJpaRepository;
    }

    @Override
    public WalletTransaction save(WalletTransaction transaction) {
        WalletTransactionJpaEntity saved =
                walletTransactionJpaRepository.save(WalletTransactionJpaEntity.fromDomain(transaction));
        return saved.toDomain();
    }

    @Override
    public Optional<WalletTransaction> findByIdempotencyKey(String idempotencyKey) {
        return walletTransactionJpaRepository.findByIdempotencyKey(idempotencyKey)
                .map(WalletTransactionJpaEntity::toDomain);
    }
}
