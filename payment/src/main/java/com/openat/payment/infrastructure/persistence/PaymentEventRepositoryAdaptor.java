package com.openat.payment.infrastructure.persistence;

import com.openat.payment.domain.model.PaymentEvent;
import com.openat.payment.domain.repository.PaymentEventRepository;
import com.openat.payment.infrastructure.persistence.entity.PaymentEventJpaEntity;
import org.springframework.stereotype.Component;

@Component
public class PaymentEventRepositoryAdaptor implements PaymentEventRepository {

    private final PaymentEventJpaRepository paymentEventJpaRepository;

    public PaymentEventRepositoryAdaptor(PaymentEventJpaRepository paymentEventJpaRepository) {
        this.paymentEventJpaRepository = paymentEventJpaRepository;
    }

    @Override
    public PaymentEvent save(PaymentEvent event) {
        PaymentEventJpaEntity saved = paymentEventJpaRepository.save(PaymentEventJpaEntity.fromDomain(event));
        return saved.toDomain();
    }
}
