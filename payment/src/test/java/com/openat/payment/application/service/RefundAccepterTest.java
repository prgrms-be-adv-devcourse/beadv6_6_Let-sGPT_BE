package com.openat.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.dto.RefundCommand;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 환불 접수(TX1)의 원자 쓰기 검증 — 환불액 선증가 + PENDING 저장, WALLET 즉시완료, 한도초과 시 저장 안 함.
class RefundAccepterTest {

  private final RefundRepository refundRepository = mock(RefundRepository.class);
  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final WalletRepository walletRepository = mock(WalletRepository.class);
  private final WalletTransactionRepository walletTransactionRepository =
      mock(WalletTransactionRepository.class);
  private final RefundFinalizer refundFinalizer = mock(RefundFinalizer.class);

  private RefundAccepter refundAccepter;

  private UUID paymentId;
  private UUID memberId;
  private Long amount;
  private String idempotencyKey;
  private String requestHash;

  @BeforeEach
  void setUp() {
    refundAccepter =
        new RefundAccepter(
            refundRepository,
            paymentRepository,
            walletRepository,
            walletTransactionRepository,
            refundFinalizer);

    paymentId = UUID.randomUUID();
    memberId = UUID.randomUUID();
    amount = 3_000L;
    idempotencyKey = "refund-idem-" + UUID.randomUUID();
    requestHash = RequestHasher.hash(paymentId.toString(), amount.toString());

    when(refundRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(walletTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  private RefundCommand command() {
    return new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);
  }

  @Test
  void WALLET_결제_환불은_PG없이_지갑에_즉시_반영하고_complete에_위임한_결과를_terminal로_반환한다() {
    Payment payment =
        Payment.builder()
            .id(paymentId)
            .memberId(memberId)
            .method(Payment.Method.WALLET)
            .status(Payment.Status.APPROVED)
            .refundedAmount(0L)
            .build();
    when(paymentRepository.tryIncreaseRefundedAmount(paymentId, amount)).thenReturn(1);
    when(walletRepository.findByMemberId(memberId))
        .thenReturn(Optional.of(Wallet.builder().memberId(memberId).balance(5_000L).build()));
    when(refundFinalizer.complete(any(), eq(payment), isNull()))
        .thenAnswer(
            inv ->
                Optional.of(
                    Refund.builder()
                        .id(inv.getArgument(0))
                        .paymentId(paymentId)
                        .amount(amount)
                        .status(Refund.Status.COMPLETE)
                        .build()));

    RefundAccepter.Acceptance acceptance = refundAccepter.accept(command(), payment, requestHash);

    assertThat(acceptance.isTerminal()).isTrue();
    assertThat(acceptance.terminalResult().status()).isEqualTo("COMPLETE");
    verify(walletRepository).charge(any(), eq(amount));
    verify(walletTransactionRepository).save(any());
    verify(refundRepository).markPgReconMatched(any(), any());
    verify(refundFinalizer).complete(any(), eq(payment), isNull());
  }

  @Test
  void PG_결제_환불은_선증가와_PENDING_저장만_하고_PG_확정없이_pending을_반환한다() {
    Payment payment =
        Payment.builder()
            .id(paymentId)
            .memberId(memberId)
            .method(Payment.Method.PG)
            .pgPaymentKey("toss-payment-key")
            .status(Payment.Status.APPROVED)
            .refundedAmount(0L)
            .build();
    when(paymentRepository.tryIncreaseRefundedAmount(paymentId, amount)).thenReturn(1);

    RefundAccepter.Acceptance acceptance = refundAccepter.accept(command(), payment, requestHash);

    assertThat(acceptance.isTerminal()).isFalse();
    assertThat(acceptance.pending().getStatus()).isEqualTo(Refund.Status.PENDING);
    verify(refundRepository).save(any());
    verifyNoInteractions(walletRepository, walletTransactionRepository, refundFinalizer);
  }

  @Test
  void 환불가능액을_초과하면_EXCEED_REFUNDABLE_AMOUNT_예외가_발생하고_Refund를_저장하지_않는다() {
    Payment payment =
        Payment.builder()
            .id(paymentId)
            .memberId(memberId)
            .method(Payment.Method.PG)
            .status(Payment.Status.APPROVED)
            .refundedAmount(9_000L)
            .build();
    when(paymentRepository.tryIncreaseRefundedAmount(paymentId, amount)).thenReturn(0);

    assertThatThrownBy(() -> refundAccepter.accept(command(), payment, requestHash))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.EXCEED_REFUNDABLE_AMOUNT);

    verify(refundRepository, never()).save(any());
    verifyNoInteractions(refundFinalizer);
  }
}
