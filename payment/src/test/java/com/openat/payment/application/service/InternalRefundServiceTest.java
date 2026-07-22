package com.openat.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.payment.application.dto.InternalRefundResult;
import com.openat.payment.application.dto.RefundCommand;
import com.openat.payment.application.dto.RefundResult;
import com.openat.payment.application.exception.PaymentErrorCode;
import com.openat.payment.application.usecase.RefundUseCase;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

// 순수 Mockito 단위테스트 — orderId 진입 어댑터(성사분 선택·상태별 분기·멱등 완화)만 검증한다.
// 실제 환불 접수/확정(조건부 UPDATE·TX 분리)은 RefundService.requestRefund에 위임되므로 여기선 위임 여부·인자만 본다.
class InternalRefundServiceTest {

  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final RefundUseCase refundUseCase = mock(RefundUseCase.class);

  private InternalRefundService internalRefundService;

  private UUID orderId;
  private UUID paymentId;
  private UUID memberId;
  private String idempotencyKey;

  @BeforeEach
  void setUp() {
    internalRefundService = new InternalRefundService(paymentRepository, refundUseCase);
    orderId = UUID.randomUUID();
    paymentId = UUID.randomUUID();
    memberId = UUID.randomUUID();
    idempotencyKey = "refund-order-" + orderId;
  }

  private Payment payment(Payment.Status status, long amount, long refundedAmount) {
    return Payment.builder()
        .id(paymentId)
        .orderId(orderId)
        .memberId(memberId)
        .amount(amount)
        .method(Payment.Method.PG)
        .status(status)
        .refundedAmount(refundedAmount)
        .build();
  }

  @Test
  void 결제_성사분이_없으면_NO_PAYMENT를_반환하고_환불을_위임하지_않는다() {
    // 모든 findByOrderIdAndStatus 미스텁 → Mockito 기본 Optional.empty()

    InternalRefundResult result = internalRefundService.refundByOrder(orderId, idempotencyKey);

    assertThat(result).isEqualTo(InternalRefundResult.NO_PAYMENT);
    verify(refundUseCase, never()).requestRefund(any());
  }

  @Test
  void 결제_진행중_PAYMENT_PENDING이면_PAYMENT_PENDING을_반환하고_환불을_위임하지_않는다() {
    when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.PAYMENT_PENDING))
        .thenReturn(Optional.of(payment(Payment.Status.PAYMENT_PENDING, 10_000L, 0L)));

    InternalRefundResult result = internalRefundService.refundByOrder(orderId, idempotencyKey);

    assertThat(result).isEqualTo(InternalRefundResult.PAYMENT_PENDING);
    verify(refundUseCase, never()).requestRefund(any());
  }

  @Test
  void APPROVED이면_전액을_환불_커맨드로_위임하고_REFUND_ACCEPTED를_반환한다() {
    when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.APPROVED))
        .thenReturn(Optional.of(payment(Payment.Status.APPROVED, 10_000L, 0L)));
    when(refundUseCase.requestRefund(any()))
        .thenReturn(new RefundResult(UUID.randomUUID(), paymentId, 10_000L, "COMPLETE"));

    InternalRefundResult result = internalRefundService.refundByOrder(orderId, idempotencyKey);

    assertThat(result).isEqualTo(InternalRefundResult.REFUND_ACCEPTED);
    ArgumentCaptor<RefundCommand> captor = ArgumentCaptor.forClass(RefundCommand.class);
    verify(refundUseCase).requestRefund(captor.capture());
    RefundCommand command = captor.getValue();
    assertThat(command.paymentId()).isEqualTo(paymentId);
    assertThat(command.memberId()).isEqualTo(memberId);
    assertThat(command.amount()).isEqualTo(10_000L);
    assertThat(command.reason()).isEqualTo("주문 취소");
    assertThat(command.idempotencyKey()).isEqualTo(idempotencyKey);
  }

  @Test
  void PARTIALLY_REFUNDED이면_남은_잔액만_환불_커맨드로_위임한다() {
    when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.PARTIALLY_REFUNDED))
        .thenReturn(Optional.of(payment(Payment.Status.PARTIALLY_REFUNDED, 10_000L, 3_000L)));
    when(refundUseCase.requestRefund(any()))
        .thenReturn(new RefundResult(UUID.randomUUID(), paymentId, 7_000L, "COMPLETE"));

    InternalRefundResult result = internalRefundService.refundByOrder(orderId, idempotencyKey);

    assertThat(result).isEqualTo(InternalRefundResult.REFUND_ACCEPTED);
    ArgumentCaptor<RefundCommand> captor = ArgumentCaptor.forClass(RefundCommand.class);
    verify(refundUseCase).requestRefund(captor.capture());
    assertThat(captor.getValue().amount()).isEqualTo(7_000L);
  }

  @Test
  void 이미_전액환불_REFUNDED이면_환불을_위임하지_않고_REFUND_ACCEPTED로_재생한다() {
    when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.REFUNDED))
        .thenReturn(Optional.of(payment(Payment.Status.REFUNDED, 10_000L, 10_000L)));

    InternalRefundResult result = internalRefundService.refundByOrder(orderId, idempotencyKey);

    assertThat(result).isEqualTo(InternalRefundResult.REFUND_ACCEPTED);
    verify(refundUseCase, never()).requestRefund(any());
  }

  @Test
  void 잔액변동으로_멱등키_충돌이_나면_내부_API는_충돌_대신_REFUND_ACCEPTED로_재생한다() {
    when(paymentRepository.findByOrderIdAndStatus(orderId, Payment.Status.APPROVED))
        .thenReturn(Optional.of(payment(Payment.Status.APPROVED, 10_000L, 0L)));
    when(refundUseCase.requestRefund(any()))
        .thenThrow(new BusinessException(PaymentErrorCode.IDEMPOTENCY_KEY_CONFLICT));

    InternalRefundResult result = internalRefundService.refundByOrder(orderId, idempotencyKey);

    assertThat(result).isEqualTo(InternalRefundResult.REFUND_ACCEPTED);
  }
}
