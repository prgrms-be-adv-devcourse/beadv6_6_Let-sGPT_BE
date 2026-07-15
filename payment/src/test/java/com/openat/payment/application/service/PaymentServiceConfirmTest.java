package com.openat.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.client.OrderValidationClient;
import com.openat.payment.application.client.OrderValidationResult;
import com.openat.payment.application.client.TossConfirmResult;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.dto.PaymentResult;
import com.openat.payment.application.dto.PgConfirmCommand;
import com.openat.payment.application.event.DomainEventPublisher;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentEventRepository;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// confirm 단일 진입점(get-or-create 예약, 7-13 plan WS-C) 전용 테스트 — WALLET/backfill은 PaymentServiceTest.
class PaymentServiceConfirmTest {

  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final WalletRepository walletRepository = mock(WalletRepository.class);
  private final WalletTransactionRepository walletTransactionRepository =
      mock(WalletTransactionRepository.class);
  private final PaymentEventRepository paymentEventRepository = mock(PaymentEventRepository.class);
  private final OrderValidationClient orderValidationClient = mock(OrderValidationClient.class);
  private final TossPaymentClient tossPaymentClient = mock(TossPaymentClient.class);
  private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
  private final PaymentFinalizer paymentFinalizer = mock(PaymentFinalizer.class);

  private PaymentService paymentService;

  private UUID orderId;
  private UUID memberId;
  private Long amount;
  private String paymentKey;
  private String keyHash;

  @BeforeEach
  void setUp() {
    paymentService =
        new PaymentService(
            paymentRepository,
            walletRepository,
            walletTransactionRepository,
            paymentEventRepository,
            orderValidationClient,
            tossPaymentClient,
            eventPublisher,
            paymentFinalizer);

    orderId = UUID.randomUUID();
    memberId = UUID.randomUUID();
    amount = 10_000L;
    paymentKey = "toss-payment-key";
    keyHash = RequestHasher.hash(paymentKey);

    when(orderValidationClient.validate(orderId, memberId, amount))
        .thenReturn(new OrderValidationResult(memberId, amount, "APPROVED", true));
  }

  private PgConfirmCommand command() {
    return new PgConfirmCommand(orderId, memberId, amount, paymentKey);
  }

  private Payment reservedPending(UUID paymentId) {
    return Payment.builder()
        .id(paymentId)
        .orderId(orderId)
        .memberId(memberId)
        .amount(amount)
        .method(Payment.Method.PG)
        .status(Payment.Status.PAYMENT_PENDING)
        .pgPaymentKey(paymentKey)
        .pgPaymentKeyHash(keyHash)
        .build();
  }

  @Test
  void 예약_성공시_토스confirm_호출후_Finalizer로_확정한다() {
    UUID paymentId = UUID.randomUUID();
    Payment approved =
        Payment.builder()
            .id(paymentId)
            .orderId(orderId)
            .memberId(memberId)
            .amount(amount)
            .method(Payment.Method.PG)
            .status(Payment.Status.APPROVED)
            .pgTxId("toss-tx-1")
            .build();
    when(paymentRepository.tryReserveForConfirm(any())).thenReturn(Optional.of(reservedPending(paymentId)));
    when(tossPaymentClient.confirmPayment(paymentKey, orderId, amount, paymentKey))
        .thenReturn(TossConfirmResult.approved("toss-tx-1"));
    when(paymentFinalizer.finalizePending(paymentId, Payment.Status.APPROVED, "toss-tx-1", null))
        .thenReturn(Optional.of(approved));

    PaymentResult result = paymentService.confirmPg(command());

    assertThat(result.status()).isEqualTo("APPROVED");
    ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
    verify(paymentRepository).tryReserveForConfirm(captor.capture());
    assertThat(captor.getValue().getOrderId()).isEqualTo(orderId);
    assertThat(captor.getValue().getPgPaymentKeyHash()).isEqualTo(keyHash);
    assertThat(captor.getValue().getStatus()).isEqualTo(Payment.Status.PAYMENT_PENDING);
    verify(paymentFinalizer).finalizePending(paymentId, Payment.Status.APPROVED, "toss-tx-1", null);
  }

  @Test
  void 레이스_PENDING_key일치면_PG호출없이_현재상태를_반환한다() {
    Payment existingPending =
        Payment.builder()
            .id(UUID.randomUUID())
            .orderId(orderId)
            .memberId(memberId)
            .amount(amount)
            .method(Payment.Method.PG)
            .status(Payment.Status.PAYMENT_PENDING)
            .pgPaymentKeyHash(keyHash)
            .build();
    when(paymentRepository.tryReserveForConfirm(any())).thenReturn(Optional.empty());
    when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(existingPending));

    PaymentResult result = paymentService.confirmPg(command());

    assertThat(result.status()).isEqualTo("PAYMENT_PENDING");
    verifyNoInteractions(tossPaymentClient);
    verifyNoInteractions(paymentFinalizer);
  }

  @Test
  void PENDING_key불일치면_PAYMENT_ATTEMPT_IN_PROGRESS_409() {
    Payment existingPending =
        Payment.builder()
            .id(UUID.randomUUID())
            .orderId(orderId)
            .memberId(memberId)
            .amount(amount)
            .method(Payment.Method.PG)
            .status(Payment.Status.PAYMENT_PENDING)
            .pgPaymentKeyHash("different-hash")
            .build();
    when(paymentRepository.tryReserveForConfirm(any())).thenReturn(Optional.empty());
    when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(existingPending));

    assertThatThrownBy(() -> paymentService.confirmPg(command()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_IN_PROGRESS);
  }

  @Test
  void 종결_key일치면_저장된_결과를_멱등반환한다() {
    Payment existingApproved =
        Payment.builder()
            .id(UUID.randomUUID())
            .orderId(orderId)
            .memberId(memberId)
            .amount(amount)
            .method(Payment.Method.PG)
            .status(Payment.Status.APPROVED)
            .pgPaymentKeyHash(keyHash)
            .build();
    when(paymentRepository.tryReserveForConfirm(any())).thenReturn(Optional.empty());
    when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(existingApproved));

    PaymentResult result = paymentService.confirmPg(command());

    assertThat(result.status()).isEqualTo("APPROVED");
    verifyNoInteractions(tossPaymentClient);
  }

  @Test
  void 종결_key불일치면_ALREADY_PROCESSED_409() {
    Payment existingApproved =
        Payment.builder()
            .id(UUID.randomUUID())
            .orderId(orderId)
            .memberId(memberId)
            .amount(amount)
            .method(Payment.Method.PG)
            .status(Payment.Status.APPROVED)
            .pgPaymentKeyHash("different-hash")
            .build();
    when(paymentRepository.tryReserveForConfirm(any())).thenReturn(Optional.empty());
    when(paymentRepository.findByOrderId(orderId)).thenReturn(Optional.of(existingApproved));

    assertThatThrownBy(() -> paymentService.confirmPg(command()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.ALREADY_PROCESSED);
  }

  @Test
  void Order검증_실패시_행을_INSERT하지_않는다() {
    when(orderValidationClient.validate(orderId, memberId, amount))
        .thenReturn(new OrderValidationResult(memberId, amount, "REJECTED", false));

    assertThatThrownBy(() -> paymentService.confirmPg(command()))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.ORDER_VALIDATION_FAILED);

    verify(paymentRepository, never()).tryReserveForConfirm(any());
    verifyNoInteractions(tossPaymentClient);
  }

  @Test
  void Finalizer가_빈값이면_lost_race로_현재상태를_반환한다() {
    UUID paymentId = UUID.randomUUID();
    // 보조 웹훅/TTL스캐너가 먼저 확정시켜버린 "현재 상태" — 이 스레드가 계산한 APPROVED와 다름.
    Payment finalizedByOther =
        Payment.builder()
            .id(paymentId)
            .orderId(orderId)
            .memberId(memberId)
            .amount(amount)
            .method(Payment.Method.PG)
            .status(Payment.Status.FAILED)
            .build();
    when(paymentRepository.tryReserveForConfirm(any())).thenReturn(Optional.of(reservedPending(paymentId)));
    when(tossPaymentClient.confirmPayment(paymentKey, orderId, amount, paymentKey))
        .thenReturn(TossConfirmResult.approved("toss-tx-1"));
    when(paymentFinalizer.finalizePending(paymentId, Payment.Status.APPROVED, "toss-tx-1", null))
        .thenReturn(Optional.empty());
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(finalizedByOther));

    PaymentResult result = paymentService.confirmPg(command());

    assertThat(result.status()).isEqualTo("FAILED");
  }
}
