package com.openat.payment.infrastructure.persistence;

import com.openat.payment.infrastructure.persistence.entity.WalletTransactionJpaEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletTransactionJpaRepository extends JpaRepository<WalletTransactionJpaEntity, UUID> {

    Optional<WalletTransactionJpaEntity> findByIdempotencyKey(String idempotencyKey);
}
