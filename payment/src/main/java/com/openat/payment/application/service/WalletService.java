package com.openat.payment.application.service;

import com.openat.payment.application.usecase.WalletUseCase;
import com.openat.payment.domain.repository.WalletRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService implements WalletUseCase {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public long getBalance(UUID memberId) {
        return walletRepository.findByMemberId(memberId)
                .map(w -> w.getBalance())
                .orElse(0L);
    }
}
