package com.openat.payment.domain.repository;

import com.openat.payment.domain.model.WalletTransaction;
import java.util.Optional;

public interface WalletTransactionRepository {

    WalletTransaction save(WalletTransaction transaction);

    Optional<WalletTransaction> findByIdempotencyKey(String idempotencyKey);
}
