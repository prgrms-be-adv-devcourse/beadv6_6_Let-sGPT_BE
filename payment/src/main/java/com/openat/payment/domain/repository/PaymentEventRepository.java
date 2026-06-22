package com.openat.payment.domain.repository;

import com.openat.payment.domain.model.PaymentEvent;

public interface PaymentEventRepository {

    PaymentEvent save(PaymentEvent event);
}
