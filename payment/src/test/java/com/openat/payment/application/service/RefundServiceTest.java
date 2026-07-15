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

import com.openat.common.error.CommonErrorCode;
import com.openat.common.exception.BusinessException;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossRefundResult;
import com.openat.payment.application.dto.RefundCommand;
import com.openat.payment.application.dto.RefundHistoryResult;
import com.openat.payment.application.dto.RefundResult;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.application.support.RequestHasher;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.model.Wallet;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import com.openat.payment.domain.repository.WalletRepository;
import com.openat.payment.domain.repository.WalletTransactionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 순수 Mockito 단위테스트(A13 목표) — plan.md E2. 확정(complete/fail)은 RefundFinalizer로 위임되므로
// (7-12 plan WS-D) 이 테스트는 접수 흐름과 Finalizer 호출 위임만 검증 — 확정 세부 로직은 RefundFinalizerTest.
class RefundServiceTest {

  private final RefundRepository refundRepository = mock(RefundRepository.class);
  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final WalletRepository walletRepository = mock(WalletRepository.class);
  private final WalletTransactionRepository walletTransactionRepository =
      mock(WalletTransactionRepository.class);
  private final TossPaymentClient tossPaymentClient = mock(TossPaymentClient.class);
  private final RefundFinalizer refundFinalizer = mock(RefundFinalizer.class);

  private RefundService refundService;

  private UUID paymentId;
  private UUID memberId;
  private Long amount;
  private String idempotencyKey;

  @BeforeEach
  void setUp() {
    refundService =
        new RefundService(
            refundRepository,
            paymentRepository,
            walletRepository,
            walletTransactionRepository,
            tossPaymentClient,
            refundFinalizer);

    paymentId = UUID.randomUUID();
    memberId = UUID.randomUUID();
    amount = 3_000L;
    idempotencyKey = "refund-idem-" + UUID.randomUUID();

    when(refundRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(walletRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(walletTransactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
  }

  @Test
  void WALLET_결제_환불은_PG를_호출하지_않고_지갑에_즉시_반영한_뒤_Finalizer_complete에_위임한다() {
    Payment payment =
        Payment.builder()
            .id(paymentId)
            .memberId(memberId)
            .orderId(UUID.randomUUID())
            .amount(10_000L)
            .method(Payment.Method.WALLET)
            .status(Payment.Status.APPROVED)
            .refundedAmount(0L)
            .build();
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
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

    RefundCommand command = new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);
    RefundResult result = refundService.requestRefund(command);

    assertThat(result.status()).isEqualTo("COMPLETE");
    verifyNoInteractions(tossPaymentClient);
    verify(walletRepository).charge(any(), eq(amount));
    verify(walletTransactionRepository).save(any());
    verify(refundFinalizer).complete(any(), eq(payment), isNull());
  }

  @Test
  void PG_결제_환불이_승인되면_Finalizer_complete에_pgRefundKey와_함께_위임한다() {
    Payment payment =
        Payment.builder()
            .id(paymentId)
            .memberId(memberId)
            .orderId(UUID.randomUUID())
            .amount(10_000L)
            .method(Payment.Method.PG)
            .pgPaymentKey("toss-payment-key")
            .status(Payment.Status.APPROVED)
            .refundedAmount(0L)
            .build();
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    when(paymentRepository.tryIncreaseRefundedAmount(paymentId, amount)).thenReturn(1);
    when(tossPaymentClient.refundPayment("toss-payment-key", amount, idempotencyKey))
        .thenReturn(TossRefundResult.complete("toss-refund-1"));
    when(refundFinalizer.complete(any(), eq(payment), eq("toss-refund-1")))
        .thenAnswer(
            inv ->
                Optional.of(
                    Refund.builder()
                        .id(inv.getArgument(0))
                        .paymentId(paymentId)
                        .amount(amount)
                        .status(Refund.Status.COMPLETE)
                        .build()));

    RefundCommand command = new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);
    RefundResult result = refundService.requestRefund(command);

    assertThat(result.status()).isEqualTo("COMPLETE");
    verifyNoInteractions(walletRepository);
    verify(refundFinalizer).complete(any(), eq(payment), eq("toss-refund-1"));
    verify(refundFinalizer, never()).fail(any(), any(), any());
  }

  @Test
  void PG_결제_환불이_거절되면_Finalizer_fail에_사유와_함께_위임한다() {
    Payment payment =
        Payment.builder()
            .id(paymentId)
            .memberId(memberId)
            .orderId(UUID.randomUUID())
            .amount(10_000L)
            .method(Payment.Method.PG)
            .pgPaymentKey("toss-payment-key")
            .status(Payment.Status.APPROVED)
            .refundedAmount(0L)
            .build();
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    when(paymentRepository.tryIncreaseRefundedAmount(paymentId, amount)).thenReturn(1);
    when(tossPaymentClient.refundPayment("toss-payment-key", amount, idempotencyKey))
        .thenReturn(TossRefundResult.failed("PG_REJECTED"));
    when(refundFinalizer.fail(any(), eq(payment), eq("PG_REJECTED")))
        .thenAnswer(
            inv ->
                Optional.of(
                    Refund.builder()
                        .id(inv.getArgument(0))
                        .paymentId(paymentId)
                        .amount(amount)
                        .status(Refund.Status.FAILED)
                        .build()));

    RefundCommand command = new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);
    RefundResult result = refundService.requestRefund(command);

    assertThat(result.status()).isEqualTo("FAILED");
    verify(refundFinalizer).fail(any(), eq(payment), eq("PG_REJECTED"));
    verify(refundFinalizer, never()).complete(any(), any(), any());
  }

  @Test
  void PG_결제_환불_응답이_불확실하면_PENDING_그대로_두고_Finalizer를_호출하지_않는다() {
    Payment payment =
        Payment.builder()
            .id(paymentId)
            .memberId(memberId)
            .orderId(UUID.randomUUID())
            .amount(10_000L)
            .method(Payment.Method.PG)
            .pgPaymentKey("toss-payment-key")
            .status(Payment.Status.APPROVED)
            .refundedAmount(0L)
            .build();
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    when(paymentRepository.tryIncreaseRefundedAmount(paymentId, amount)).thenReturn(1);
    when(tossPaymentClient.refundPayment("toss-payment-key", amount, idempotencyKey))
        .thenReturn(TossRefundResult.unknown());

    RefundCommand command = new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);
    RefundResult result = refundService.requestRefund(command);

    assertThat(result.status()).isEqualTo("PENDING");
    verifyNoInteractions(refundFinalizer);
  }

  @Test
  void 환불가능액을_초과하면_EXCEED_REFUNDABLE_AMOUNT_예외가_발생하고_Refund를_저장하지_않는다() {
    Payment payment =
        Payment.builder()
            .id(paymentId)
            .memberId(memberId)
            .amount(10_000L)
            .method(Payment.Method.PG)
            .status(Payment.Status.APPROVED)
            .refundedAmount(9_000L)
            .build();
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    when(paymentRepository.tryIncreaseRefundedAmount(paymentId, amount)).thenReturn(0);

    RefundCommand command = new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);

    assertThatThrownBy(() -> refundService.requestRefund(command))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.EXCEED_REFUNDABLE_AMOUNT);

    verify(refundRepository, never()).save(any());
    verifyNoInteractions(tossPaymentClient);
  }

  @Test
  void 결제_소유자가_다르면_FORBIDDEN_예외가_발생하고_환불가능액_검증을_시도하지_않는다() {
    Payment payment =
        Payment.builder()
            .id(paymentId)
            .memberId(UUID.randomUUID())
            .amount(10_000L)
            .method(Payment.Method.PG)
            .status(Payment.Status.APPROVED)
            .refundedAmount(0L)
            .build();
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));

    RefundCommand command = new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);

    assertThatThrownBy(() -> refundService.requestRefund(command))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.FORBIDDEN);

    verify(paymentRepository, never()).tryIncreaseRefundedAmount(any(), any());
  }

  @Test
  void 대상_결제가_없으면_NOT_FOUND_예외가_발생한다() {
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

    RefundCommand command = new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);

    assertThatThrownBy(() -> refundService.requestRefund(command))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.NOT_FOUND);
  }

  @Test
  void 같은_멱등키_같은_바디로_재요청하면_기존_결과를_그대로_반환하고_Payment를_조회하지_않는다() {
    String requestHash = RequestHasher.hash(paymentId.toString(), amount.toString());
    Refund existing =
        Refund.builder()
            .paymentId(paymentId)
            .amount(amount)
            .status(Refund.Status.COMPLETE)
            .idempotencyKey(idempotencyKey)
            .requestHash(requestHash)
            .build();
    when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

    RefundCommand command = new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);
    RefundResult result = refundService.requestRefund(command);

    assertThat(result.refundId()).isEqualTo(existing.getId());
    assertThat(result.status()).isEqualTo("COMPLETE");
    verifyNoInteractions(paymentRepository);
  }

  @Test
  void 같은_멱등키_다른_바디로_재요청하면_IDEMPOTENCY_KEY_CONFLICT_예외가_발생한다() {
    String differentHash = RequestHasher.hash(paymentId.toString(), "9999");
    Refund existing =
        Refund.builder()
            .paymentId(paymentId)
            .amount(9_999L)
            .status(Refund.Status.COMPLETE)
            .idempotencyKey(idempotencyKey)
            .requestHash(differentHash)
            .build();
    when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.of(existing));

    RefundCommand command = new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);

    assertThatThrownBy(() -> refundService.requestRefund(command))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT);
  }

  @Test
  void getRefund_정상_조회() {
    UUID refundId = UUID.randomUUID();
    Refund refund =
        Refund.builder()
            .id(refundId)
            .paymentId(paymentId)
            .amount(amount)
            .status(Refund.Status.COMPLETE)
            .build();
    when(refundRepository.findById(refundId)).thenReturn(Optional.of(refund));

    RefundResult result = refundService.getRefund(refundId);

    assertThat(result.refundId()).isEqualTo(refundId);
    assertThat(result.status()).isEqualTo("COMPLETE");
  }

  @Test
  void getRefund_대상이_없으면_NOT_FOUND_예외가_발생한다() {
    UUID refundId = UUID.randomUUID();
    when(refundRepository.findById(refundId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> refundService.getRefund(refundId))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(CommonErrorCode.NOT_FOUND);
  }

  @Test
  void getRefundHistories_totalPages를_올바르게_계산한다() {
    Refund r1 =
        Refund.builder().paymentId(paymentId).amount(1_000L).status(Refund.Status.COMPLETE).build();
    when(refundRepository.findByMemberId(memberId, 0, 20)).thenReturn(List.of(r1));
    when(refundRepository.countByMemberId(memberId)).thenReturn(21L);

    RefundHistoryResult result = refundService.getRefundHistories(memberId, 0, 20);

    assertThat(result.content()).hasSize(1);
    assertThat(result.totalPages()).isEqualTo(2);
  }
}
