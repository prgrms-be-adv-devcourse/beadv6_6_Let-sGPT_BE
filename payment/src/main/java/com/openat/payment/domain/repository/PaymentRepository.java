package com.openat.payment.domain.repository;

import com.openat.payment.domain.model.Payment;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository {

    Payment save(Payment payment);

    Optional<Payment> findById(UUID id);
}
