package com.openat.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.payment.application.dto.RefundCompletedPayload;
import com.openat.payment.application.dto.RefundFailedPayload;
import com.openat.payment.application.event.DomainEventPublisher;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// 프레임워크 의존 없는 순수 Mockito 단위테스트(7-12 plan WS-D) — §4.1 환불 결함(lost-race에 한도 원복)의 green 증명 지점.
class RefundFinalizerTest {

  private final RefundRepository refundRepository = mock(RefundRepository.class);
  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
  private final RefundFinalizer finalizer =
      new RefundFinalizer(refundRepository, paymentRepository, eventPublisher);

  private final Payment payment =
      Payment.builder()
          .id(UUID.randomUUID())
          .orderId(UUID.randomUUID())
          .memberId(UUID.randomUUID())
          .amount(10_000L)
          .build();

  @Test
  void complete가_승리하면_완료이벤트와_정산이벤트를_발행한다() {
    UUID refundId = UUID.randomUUID();
    Refund updated =
        Refund.builder()
            .id(refundId)
            .paymentId(payment.getId())
            .amount(3_000L)
            .status(Refund.Status.COMPLETE)
            .build();
    when(refundRepository.tryTransitionFromPending(
            eq(refundId), eq(Refund.Status.COMPLETE), any(), any()))
        .thenReturn(1);
    when(refundRepository.findById(refundId)).thenReturn(Optional.of(updated));

    Optional<Refund> result = finalizer.complete(refundId, payment, "pg-refund-key");

    assertThat(result).contains(updated);
    verify(eventPublisher)
        .publish(
            eq("REFUND"),
            eq(refundId),
            eq("refund.completed.events"),
            any(RefundCompletedPayload.class));
    verify(eventPublisher)
        .publish(eq("REFUND"), eq(refundId), eq("payment.settlement.events"), any());
  }

  @Test
  void 환불웹훅이_lost_race면_환불한도를_원복하지_않는다() {
    UUID refundId = UUID.randomUUID();
    when(refundRepository.tryTransitionFromPending(
            eq(refundId), eq(Refund.Status.FAILED), any(), any()))
        .thenReturn(0);

    Optional<Refund> result = finalizer.fail(refundId, payment, "PG_REJECTED");

    assertThat(result).isEmpty();
    verify(paymentRepository, never()).tryDecreaseRefundedAmount(any(), any());
    verify(eventPublisher, never()).publish(any(), any(), any(), any());
  }

  @Test
  void 환불웹훅이_정식_거절이면_한도원복과_failed_이벤트를_발행한다() {
    UUID refundId = UUID.randomUUID();
    Refund updated =
        Refund.builder()
            .id(refundId)
            .paymentId(payment.getId())
            .amount(3_000L)
            .status(Refund.Status.FAILED)
            .build();
    when(refundRepository.tryTransitionFromPending(
            eq(refundId), eq(Refund.Status.FAILED), any(), any()))
        .thenReturn(1);
    when(refundRepository.findById(refundId)).thenReturn(Optional.of(updated));

    Optional<Refund> result = finalizer.fail(refundId, payment, "PG_REJECTED");

    assertThat(result).contains(updated);
    verify(paymentRepository).tryDecreaseRefundedAmount(payment.getId(), 3_000L);
    verify(eventPublisher)
        .publish(
            eq("REFUND"), eq(refundId), eq("refund.failed.events"), any(RefundFailedPayload.class));
  }
}
