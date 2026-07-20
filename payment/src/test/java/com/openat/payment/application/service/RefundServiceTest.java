package com.openat.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 순수 Mockito 단위테스트(A13 목표). 접수(환불액 선증가 + PENDING 저장)는 RefundAccepter로, 확정(complete/fail)은
// RefundFinalizer로 위임되므로 이 테스트는 requestRefund의 오케스트레이션(멱등/소유자/PG 분기 위임)만 검증한다 —
// 접수 원자성은 RefundAccepterTest, 확정 세부는 RefundFinalizerTest.
class RefundServiceTest {

  private final RefundRepository refundRepository = mock(RefundRepository.class);
  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final TossPaymentClient tossPaymentClient = mock(TossPaymentClient.class);
  private final RefundFinalizer refundFinalizer = mock(RefundFinalizer.class);
  private final RefundAccepter refundAccepter = mock(RefundAccepter.class);

  private RefundService refundService;

  private UUID paymentId;
  private UUID memberId;
  private Long amount;
  private String idempotencyKey;

  @BeforeEach
  void setUp() {
    refundService =
        new RefundService(
            refundRepository, paymentRepository, tossPaymentClient, refundFinalizer, refundAccepter);

    paymentId = UUID.randomUUID();
    memberId = UUID.randomUUID();
    amount = 3_000L;
    idempotencyKey = "refund-idem-" + UUID.randomUUID();

    when(refundRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(Optional.empty());
  }

  private Payment pgPayment() {
    return Payment.builder()
        .id(paymentId)
        .memberId(memberId)
        .orderId(UUID.randomUUID())
        .amount(10_000L)
        .method(Payment.Method.PG)
        .pgPaymentKey("toss-payment-key")
        .status(Payment.Status.APPROVED)
        .refundedAmount(0L)
        .build();
  }

  private Refund pendingRefund(UUID refundId) {
    return Refund.builder()
        .id(refundId)
        .paymentId(paymentId)
        .amount(amount)
        .status(Refund.Status.PENDING)
        .idempotencyKey(idempotencyKey)
        .build();
  }

  @Test
  void 접수가_즉시_완료를_반환하면_그대로_반환하고_PG를_호출하지_않는다() {
    UUID refundId = UUID.randomUUID();
    Payment payment =
        Payment.builder()
            .id(paymentId)
            .memberId(memberId)
            .method(Payment.Method.WALLET)
            .status(Payment.Status.APPROVED)
            .refundedAmount(0L)
            .build();
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    when(refundAccepter.accept(any(), any(), any()))
        .thenReturn(
            RefundAccepter.Acceptance.terminal(
                new RefundResult(refundId, paymentId, amount, "COMPLETE")));

    RefundCommand command = new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);
    RefundResult result = refundService.requestRefund(command);

    assertThat(result.status()).isEqualTo("COMPLETE");
    verifyNoInteractions(tossPaymentClient, refundFinalizer);
  }

  @Test
  void PG_결제_환불이_승인되면_Finalizer_complete에_pgRefundKey와_함께_위임한다() {
    UUID refundId = UUID.randomUUID();
    Payment payment = pgPayment();
    Refund pending = pendingRefund(refundId);
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    when(refundAccepter.accept(any(), any(), any()))
        .thenReturn(RefundAccepter.Acceptance.pendingPg(pending));
    when(tossPaymentClient.refundPayment("toss-payment-key", amount, idempotencyKey))
        .thenReturn(TossRefundResult.complete("toss-refund-1"));
    when(refundFinalizer.complete(eq(refundId), eq(payment), eq("toss-refund-1")))
        .thenReturn(
            Optional.of(
                Refund.builder()
                    .id(refundId)
                    .paymentId(paymentId)
                    .amount(amount)
                    .status(Refund.Status.COMPLETE)
                    .build()));

    RefundCommand command = new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);
    RefundResult result = refundService.requestRefund(command);

    assertThat(result.status()).isEqualTo("COMPLETE");
    verify(refundFinalizer).complete(eq(refundId), eq(payment), eq("toss-refund-1"));
    verify(refundFinalizer, never()).fail(any(), any(), any());
  }

  @Test
  void PG_결제_환불이_거절되면_Finalizer_fail에_사유와_함께_위임한다() {
    UUID refundId = UUID.randomUUID();
    Payment payment = pgPayment();
    Refund pending = pendingRefund(refundId);
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    when(refundAccepter.accept(any(), any(), any()))
        .thenReturn(RefundAccepter.Acceptance.pendingPg(pending));
    when(tossPaymentClient.refundPayment("toss-payment-key", amount, idempotencyKey))
        .thenReturn(TossRefundResult.failed("PG_REJECTED"));
    when(refundFinalizer.fail(eq(refundId), eq(payment), eq("PG_REJECTED")))
        .thenReturn(
            Optional.of(
                Refund.builder()
                    .id(refundId)
                    .paymentId(paymentId)
                    .amount(amount)
                    .status(Refund.Status.FAILED)
                    .build()));

    RefundCommand command = new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);
    RefundResult result = refundService.requestRefund(command);

    assertThat(result.status()).isEqualTo("FAILED");
    verify(refundFinalizer).fail(eq(refundId), eq(payment), eq("PG_REJECTED"));
    verify(refundFinalizer, never()).complete(any(), any(), any());
  }

  @Test
  void PG_결제_환불_응답이_불확실하면_PENDING_그대로_두고_Finalizer를_호출하지_않는다() {
    UUID refundId = UUID.randomUUID();
    Payment payment = pgPayment();
    Refund pending = pendingRefund(refundId);
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    when(refundAccepter.accept(any(), any(), any()))
        .thenReturn(RefundAccepter.Acceptance.pendingPg(pending));
    when(tossPaymentClient.refundPayment("toss-payment-key", amount, idempotencyKey))
        .thenReturn(TossRefundResult.unknown());

    RefundCommand command = new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);
    RefundResult result = refundService.requestRefund(command);

    assertThat(result.status()).isEqualTo("PENDING");
    verifyNoInteractions(refundFinalizer);
  }

  @Test
  void 접수에서_환불가능액을_초과하면_예외가_그대로_전파되고_PG를_호출하지_않는다() {
    Payment payment = pgPayment();
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    when(refundAccepter.accept(any(), any(), any()))
        .thenThrow(new BusinessException(PaymentErrorCode.EXCEED_REFUNDABLE_AMOUNT));

    RefundCommand command = new RefundCommand(paymentId, memberId, amount, "단순변심", idempotencyKey);

    assertThatThrownBy(() -> refundService.requestRefund(command))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(PaymentErrorCode.EXCEED_REFUNDABLE_AMOUNT);

    verifyNoInteractions(tossPaymentClient);
  }

  @Test
  void 결제_소유자가_다르면_FORBIDDEN_예외가_발생하고_접수를_시도하지_않는다() {
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

    verifyNoInteractions(refundAccepter, tossPaymentClient);
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
    verifyNoInteractions(paymentRepository, refundAccepter);
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
