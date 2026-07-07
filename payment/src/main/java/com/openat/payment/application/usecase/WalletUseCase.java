package com.openat.payment.application.usecase;

import java.util.UUID;

public interface WalletUseCase {

    long getBalance(UUID memberId);
}
