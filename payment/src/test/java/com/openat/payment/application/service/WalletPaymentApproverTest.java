package com.openat.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.dto.PayWithWalletCommand;
import com.openat.payment.application.dto.PaymentCompletedPayload;
import com.openat.payment.application.event.DomainEventPublisher;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.repository.PaymentEventRepository;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// WALLET 즉시승인의 DB 쓰기 경로(차감·거래내역·결제저장·PaymentEvent·completed 발행) 단위테스트.
// PaymentService에서 이관된 write-path 검증 — 오케스트레이션은 PaymentServiceTest.
class WalletPaymentApproverTest {

  private static final String COMPLETED_TOPIC = "payment.completed.events";

  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final WalletRepository walletRepository = mock(WalletRepository.class);
  private final WalletTransactionRepository walletTransactionRepository =
      mock(WalletTransactionRepository.class);
  private final PaymentEventRepository paymentEventRepository = mock(PaymentEventRepository.class);
  private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);

  private WalletPaymentApprover approver;

  private UUID orderId;
  private UUID memberId;
  private Long amount;
  private String idempotencyKey;
  private String requestHash;

  @BeforeEach
  void setUp() {
    approver =
        new WalletPaymentApprover(
            paymentRepository,
            walletRepository,
            walletTransactionRepository,
            paymentEventRepository,
            eventPublisher);

    orderId = UUID.randomUUID();
    memberId = UUID.randomUUID();
    amount = 10_000L;
    idempotencyKey = "idem-" + UUID.randomUUID();
    requestHash = "req-hash";

    // 빌더가 만든 객체를 그대로 돌려주는 저장 스텁.
    when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(walletTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(paymentEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  @Test
  void 잔액충분이면_차감_거래내역_결제저장_PaymentEvent_completed이벤트까지_수행된다() {
    PayWithWalletCommand command =
        new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
    Wallet wallet = Wallet.builder().memberId(memberId).balance(10_000L).build();
    when(walletRepository.findOrCreateByMemberId(memberId)).thenReturn(wallet);
    when(walletRepository.tryDeduct(any(), eq(amount))).thenReturn(1);
    when(walletRepository.findByMemberId(memberId))
        .thenReturn(Optional.of(Wallet.builder().memberId(memberId).balance(0L).build()));

    Payment saved = approver.deductAndApprove(command, requestHash);

    assertThat(saved.getStatus()).isEqualTo(Payment.Status.APPROVED);
    assertThat(saved.getAmount()).isEqualTo(amount);
    verify(walletRepository).tryDeduct(any(), eq(amount));
    verify(walletTransactionRepository).save(any());
    verify(paymentRepository).save(any());
    verify(paymentEventRepository).save(any());
    verify(eventPublisher)
        .publish(eq("PAYMENT"), any(), eq(COMPLETED_TOPIC), any(PaymentCompletedPayload.class));
  }

  @Test
  void 잔액부족이면_INSUFFICIENT_BALANCE_예외가_발생하고_이후_저장_발행은_호출되지_않는다() {
    PayWithWalletCommand command =
        new PayWithWalletCommand(orderId, memberId, amount, idempotencyKey);
    when(walletRepository.findOrCreateByMemberId(memberId))
        .thenReturn(Wallet.builder().memberId(memberId).balance(0L).build());
    when(walletRepository.tryDeduct(any(), eq(amount))).thenReturn(0);

    assertThatThrownBy(() -> approver.deductAndApprove(command, requestHash))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.INSUFFICIENT_BALANCE);

    verify(walletTransactionRepository, never()).save(any());
    verify(paymentRepository, never()).save(any());
    verify(paymentEventRepository, never()).save(any());
    verify(eventPublisher, never()).publish(any(), any(), any(), any());
  }
}
