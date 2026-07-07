package com.openat.payment.infrastructure.scheduler;

import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.model.WalletCharge;
import com.openat.payment.domain.model.WalletTransaction;
import com.openat.payment.domain.repository.WalletChargeRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// PaymentTtlFinalizer와 동일한 이유로 분리(Spring AOP self-invocation 문제 회피).
// WalletCharge는 Kafka 이벤트 없음(api_event_specification.md) — 승인 시 Wallet 잔액 반영만 처리.
@Component
public class WalletChargeTtlFinalizer {

    private final WalletChargeRepository walletChargeRepository;
    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    public WalletChargeTtlFinalizer(WalletChargeRepository walletChargeRepository,
            WalletRepository walletRepository, WalletTransactionRepository walletTransactionRepository) {
        this.walletChargeRepository = walletChargeRepository;
        this.walletRepository = walletRepository;
        this.walletTransactionRepository = walletTransactionRepository;
    }

    @Transactional
    public void finalizePending(WalletCharge charge, WalletCharge.Status newStatus, String pgTxId) {
        // 하자드10 — confirm/보조웹훅과 동시에 같은 row를 만질 수 있어 조건부 UPDATE로 원자처리.
        int affected = walletChargeRepository.tryTransitionFromPending(charge.getId(), newStatus, pgTxId);
        if (affected == 0) {
            return; // 이미 다른 경로가 먼저 확정함
        }

        if (newStatus == WalletCharge.Status.APPROVED) {
            // confirmCharge/WalletChargeWebhookHandler와 동일한 후속처리 — 승인 즉시 Wallet 잔액 반영.
            Wallet wallet = walletRepository.findByMemberId(charge.getMemberId())
                    .orElseGet(() -> walletRepository.save(Wallet.builder()
                            .memberId(charge.getMemberId())
                            .balance(0L)
                            .createdAt(LocalDateTime.now())
                            .build()));
            walletRepository.charge(wallet.getId(), charge.getAmount());
            long balanceAfter = wallet.getBalance() + charge.getAmount();

            walletTransactionRepository.save(WalletTransaction.builder()
                    .walletId(wallet.getId())
                    .type(WalletTransaction.Type.CHARGE)
                    .amount(charge.getAmount())
                    .balanceAfter(balanceAfter)
                    .idempotencyKey(charge.getIdempotencyKey())
                    .createdAt(LocalDateTime.now())
                    .build());
        }
    }
}
