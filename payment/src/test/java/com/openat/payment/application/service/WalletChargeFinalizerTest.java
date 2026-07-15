package com.openat.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.model.WalletCharge;
import com.openat.payment.domain.repository.WalletChargeRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// 프레임워크 의존 없는 순수 Mockito 단위테스트(7-12 plan WS-D).
class WalletChargeFinalizerTest {

  private final WalletChargeRepository walletChargeRepository = mock(WalletChargeRepository.class);
  private final WalletRepository walletRepository = mock(WalletRepository.class);
  private final WalletTransactionRepository walletTransactionRepository =
      mock(WalletTransactionRepository.class);
  private final WalletChargeFinalizer finalizer =
      new WalletChargeFinalizer(
          walletChargeRepository, walletRepository, walletTransactionRepository);

  @Test
  void APPROVED_승리시에만_지갑에_반영하고_balanceAfter는_재조회값을_기록한다() {
    UUID chargeId = UUID.randomUUID();
    UUID memberId = UUID.randomUUID();
    UUID walletId = UUID.randomUUID();
    WalletCharge updated =
        WalletCharge.builder()
            .id(chargeId)
            .memberId(memberId)
            .amount(5_000L)
            .status(WalletCharge.Status.APPROVED)
            .idempotencyKey("idem-1")
            .build();
    Wallet wallet = Wallet.builder().id(walletId).memberId(memberId).balance(10_000L).build();
    Wallet reloaded = Wallet.builder().id(walletId).memberId(memberId).balance(15_000L).build();

    when(walletChargeRepository.tryTransitionFromPending(
            eq(chargeId), eq(WalletCharge.Status.APPROVED), any()))
        .thenReturn(1);
    when(walletChargeRepository.findById(chargeId)).thenReturn(Optional.of(updated));
    when(walletRepository.findOrCreateByMemberId(memberId)).thenReturn(wallet);
    when(walletRepository.findByMemberId(memberId)).thenReturn(Optional.of(reloaded));

    Optional<WalletCharge> result =
        finalizer.finalizePending(chargeId, WalletCharge.Status.APPROVED, "tx-1");

    assertThat(result).contains(updated);
    verify(walletRepository).charge(walletId, 5_000L);
    org.mockito.ArgumentCaptor<com.openat.payment.domain.model.WalletTransaction> captor =
        org.mockito.ArgumentCaptor.forClass(
            com.openat.payment.domain.model.WalletTransaction.class);
    verify(walletTransactionRepository).save(captor.capture());
    assertThat(captor.getValue().getBalanceAfter()).isEqualTo(15_000L);
  }

  @Test
  void lost_race면_지갑에_반영하지_않는다() {
    UUID chargeId = UUID.randomUUID();
    when(walletChargeRepository.tryTransitionFromPending(
            eq(chargeId), eq(WalletCharge.Status.APPROVED), any()))
        .thenReturn(0);

    Optional<WalletCharge> result =
        finalizer.finalizePending(chargeId, WalletCharge.Status.APPROVED, "tx-1");

    assertThat(result).isEmpty();
    verify(walletRepository, never()).findOrCreateByMemberId(any());
    verify(walletTransactionRepository, never()).save(any());
  }

  @Test
  void FAILED_승리시에는_지갑에_반영하지_않는다() {
    UUID chargeId = UUID.randomUUID();
    WalletCharge updated =
        WalletCharge.builder().id(chargeId).status(WalletCharge.Status.FAILED).build();
    when(walletChargeRepository.tryTransitionFromPending(
            eq(chargeId), eq(WalletCharge.Status.FAILED), any()))
        .thenReturn(1);
    when(walletChargeRepository.findById(chargeId)).thenReturn(Optional.of(updated));

    Optional<WalletCharge> result =
        finalizer.finalizePending(chargeId, WalletCharge.Status.FAILED, null);

    assertThat(result).contains(updated);
    verify(walletRepository, never()).findOrCreateByMemberId(any());
  }
}
