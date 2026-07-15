package com.openat.payment.application.service;

import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.model.WalletCharge;
import com.openat.payment.domain.model.WalletTransaction;
import com.openat.payment.domain.repository.WalletChargeRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 확정 전담(PaymentFinalizer와 동일 원칙, 7-12 plan WS-D) — confirmCharge 동기응답/WalletChargeWebhookHandler
// 보조채널/TTL스캐너 3경로가 전부 이걸 호출한다. 이긴 경우 + APPROVED일 때만 지갑 반영(§4.2 결함 수정 지점 —
// 종전 3곳 복제를 여기 한 곳으로 수렴).
@Component
public class WalletChargeFinalizer {

  private final WalletChargeRepository walletChargeRepository;
  private final WalletRepository walletRepository;
  private final WalletTransactionRepository walletTransactionRepository;

  public WalletChargeFinalizer(
      WalletChargeRepository walletChargeRepository,
      WalletRepository walletRepository,
      WalletTransactionRepository walletTransactionRepository) {
    this.walletChargeRepository = walletChargeRepository;
    this.walletRepository = walletRepository;
    this.walletTransactionRepository = walletTransactionRepository;
  }

  @Transactional
  public Optional<WalletCharge> finalizePending(
      UUID chargeId, WalletCharge.Status newStatus, String pgTxId) {
    if (!WalletCharge.Status.PENDING.canTransitionTo(newStatus)) {
      throw new IllegalStateException("불법 전이: PENDING -> " + newStatus);
    }
    int affected = walletChargeRepository.tryTransitionFromPending(chargeId, newStatus, pgTxId);
    if (affected == 0) {
      return Optional.empty(); // lost-race — 지갑 반영 없음
    }
    WalletCharge updated =
        walletChargeRepository
            .findById(chargeId)
            .orElseThrow(() -> new IllegalStateException("전이 직후 WalletCharge 소실: " + chargeId));

    if (newStatus == WalletCharge.Status.APPROVED) {
      Wallet wallet = walletRepository.findOrCreateByMemberId(updated.getMemberId());
      walletRepository.charge(wallet.getId(), updated.getAmount());
      // D3 — UPDATE 성공 후 같은 TX 재조회(row lock이 커밋까지 유지되므로 재조회 값이 정답).
      Wallet reloaded = walletRepository.findByMemberId(updated.getMemberId()).orElse(wallet);

      walletTransactionRepository.save(
          WalletTransaction.chargeOf(
              wallet.getId(),
              updated.getAmount(),
              reloaded.getBalance(),
              updated.getIdempotencyKey()));
    }

    return Optional.of(updated);
  }
}
