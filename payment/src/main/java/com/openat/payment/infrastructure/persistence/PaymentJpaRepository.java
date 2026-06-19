package com.openat.payment.infrastructure.persistence;

import com.openat.payment.infrastructure.persistence.entity.PaymentJpaEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentJpaRepository extends JpaRepository<PaymentJpaEntity, UUID> {
}
