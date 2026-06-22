package com.openat.payment.infrastructure.persistence;

import com.openat.payment.infrastructure.persistence.entity.WalletChargeJpaEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WalletChargeJpaRepository extends JpaRepository<WalletChargeJpaEntity, UUID> {

    Optional<WalletChargeJpaEntity> findByIdempotencyKey(String idempotencyKey);
}
