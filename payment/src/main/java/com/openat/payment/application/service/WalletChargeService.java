package com.openat.payment.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.dto.ChargeWalletCommand;
import com.openat.payment.application.dto.WalletChargeResult;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.application.usecase.WalletChargeUseCase;
import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.model.WalletCharge;
import com.openat.payment.domain.model.WalletTransaction;
import com.openat.payment.domain.repository.WalletChargeRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// MOCK 충전(§4) — PG 의존 없는 가장 단순한 흐름, 항상 즉시 APPROVED.
@Service
public class WalletChargeService implements WalletChargeUseCase {

    private final WalletChargeRepository walletChargeRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    public WalletChargeService(WalletChargeRepository walletChargeRepository, WalletRepository walletRepository,
            WalletTransactionRepository walletTransactionRepository) {
        this.walletChargeRepository = walletChargeRepository;
        this.walletRepository = walletRepository;
        this.walletTransactionRepository = walletTransactionRepository;
    }

    @Override
    @Transactional
    public WalletChargeResult chargeMock(ChargeWalletCommand command) {
        String requestHash = RequestHasher.hash(
                command.memberId().toString(), command.amount().toString(), WalletCharge.Method.MOCK.name());

        Optional<WalletCharge> existing = walletChargeRepository.findByIdempotencyKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash);
        }

        Wallet wallet = getOrCreateWallet(command.memberId());
        walletRepository.charge(wallet.getId(), command.amount());
        long balanceAfter = wallet.getBalance() + command.amount();

        LocalDateTime now = LocalDateTime.now();

        walletTransactionRepository.save(WalletTransaction.builder()
                .walletId(wallet.getId())
                .type(WalletTransaction.Type.CHARGE)
                .amount(command.amount())
                .balanceAfter(balanceAfter)
                .idempotencyKey(command.idempotencyKey())
                .createdAt(now)
                .build());

        WalletCharge saved = walletChargeRepository.save(WalletCharge.builder()
                .memberId(command.memberId())
                .amount(command.amount())
                .method(WalletCharge.Method.MOCK)
                .status(WalletCharge.Status.APPROVED)
                .idempotencyKey(command.idempotencyKey())
                .requestHash(requestHash)
                .createdAt(now)
                .updatedAt(now)
                .build());

        return new WalletChargeResult(saved.getId(), saved.getStatus().name(), saved.getAmount());
    }

    private WalletChargeResult replayOrConflict(WalletCharge existing, String requestHash) {
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return new WalletChargeResult(existing.getId(), existing.getStatus().name(), existing.getAmount());
    }

    private Wallet getOrCreateWallet(UUID memberId) {
        return walletRepository.findByMemberId(memberId)
                .orElseGet(() -> walletRepository.save(Wallet.builder()
                        .memberId(memberId)
                        .balance(0L)
                        .createdAt(LocalDateTime.now())
                        .build()));
    }
}
