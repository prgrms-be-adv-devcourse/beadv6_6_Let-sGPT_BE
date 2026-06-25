package com.openat.payment.infrastructure.persistence;

import com.openat.payment.infrastructure.persistence.entity.PaymentEventJpaEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentEventJpaRepository extends JpaRepository<PaymentEventJpaEntity, UUID> {
}
