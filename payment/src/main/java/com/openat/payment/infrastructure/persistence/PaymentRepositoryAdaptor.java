package com.openat.payment.infrastructure.persistence;

import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.infrastructure.persistence.entity.PaymentJpaEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class PaymentRepositoryAdaptor implements PaymentRepository {

    private final PaymentJpaRepository paymentJpaRepository;

    public PaymentRepositoryAdaptor(PaymentJpaRepository paymentJpaRepository) {
        this.paymentJpaRepository = paymentJpaRepository;
    }

    @Override
    public Payment save(Payment payment) {
        PaymentJpaEntity saved = paymentJpaRepository.save(PaymentJpaEntity.fromDomain(payment));
        return saved.toDomain();
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return paymentJpaRepository.findById(id).map(PaymentJpaEntity::toDomain);
    }
}
