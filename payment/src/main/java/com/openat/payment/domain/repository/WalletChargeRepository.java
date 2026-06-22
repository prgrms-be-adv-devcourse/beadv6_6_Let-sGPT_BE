package com.openat.payment.domain.repository;

import com.openat.payment.domain.model.WalletCharge;
import java.util.Optional;

public interface WalletChargeRepository {

    WalletCharge save(WalletCharge charge);

    Optional<WalletCharge> findByIdempotencyKey(String idempotencyKey);
}
