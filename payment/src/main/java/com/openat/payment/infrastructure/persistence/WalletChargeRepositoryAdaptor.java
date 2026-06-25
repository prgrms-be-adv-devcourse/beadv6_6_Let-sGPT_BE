package com.openat.payment.infrastructure.persistence;

import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.domain.model.WalletCharge;
import com.openat.payment.domain.repository.WalletChargeRepository;
import com.openat.payment.infrastructure.persistence.entity.WalletChargeJpaEntity;
import java.util.Optional;
import java.util.UUID;
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
    public Optional<WalletCharge> findById(UUID id) {
        return walletChargeJpaRepository.findById(id).map(WalletChargeJpaEntity::toDomain);
    }

    @Override
    public Optional<WalletCharge> findByIdempotencyKey(String idempotencyKey) {
        return walletChargeJpaRepository.findByIdempotencyKey(idempotencyKey).map(WalletChargeJpaEntity::toDomain);
    }

    @Override
    public Optional<WalletCharge> findByPgPaymentKey(String pgPaymentKey) {
        // pgPaymentKey 컬럼은 암호화(비결정적 IV)되어 등호조회 불가 — 평문 해시(결정적)로 조회.
        return walletChargeJpaRepository.findByPgPaymentKeyHash(RequestHasher.hash(pgPaymentKey))
                .map(WalletChargeJpaEntity::toDomain);
    }

    @Override
    public void updatePgPaymentKey(UUID id, String pgPaymentKey) {
        walletChargeJpaRepository.updatePgPaymentKey(id, pgPaymentKey, RequestHasher.hash(pgPaymentKey));
    }

    @Override
    public int tryTransitionFromPending(UUID id, WalletCharge.Status newStatus, String pgTxId) {
        return walletChargeJpaRepository.tryTransitionFromPending(id, newStatus, pgTxId);
    }
}
