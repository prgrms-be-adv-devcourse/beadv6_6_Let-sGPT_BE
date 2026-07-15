package com.openat.payment.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.payment.application.dto.PaymentCompletedPayload;
import com.openat.payment.application.event.DomainEventPublisher;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

// 프레임워크 의존 없는 순수 Mockito 단위테스트(7-12 plan WS-D) — §4.1 결함(lost-race에 부수효과 실행)의 green 증명 지점.
class PaymentFinalizerTest {

  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final DomainEventPublisher eventPublisher = mock(DomainEventPublisher.class);
  private final PaymentFinalizer finalizer =
      new PaymentFinalizer(paymentRepository, eventPublisher);

  private static final String COMPLETED_TOPIC = "payment.completed.events";
  private static final String FAILED_TOPIC = "payment.failed.events";

  @Test
  void 전이에_승리하면_승인이벤트를_1회_발행하고_확정된_Payment를_반환한다() {
    UUID paymentId = UUID.randomUUID();
    Payment updated =
        Payment.builder()
            .id(paymentId)
            .status(Payment.Status.APPROVED)
            .method(Payment.Method.PG)
            .build();
    when(paymentRepository.tryTransitionFromPending(
            eq(paymentId), eq(Payment.Status.APPROVED), eq("tx-1"), any()))
        .thenReturn(1);
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(updated));

    Optional<Payment> result =
        finalizer.finalizePending(paymentId, Payment.Status.APPROVED, "tx-1", null);

    assertThat(result).contains(updated);
    verify(eventPublisher)
        .publish(
            eq("PAYMENT"), eq(paymentId), eq(COMPLETED_TOPIC), any(PaymentCompletedPayload.class));
  }

  @Test
  void 웹훅이_lost_race면_failed_이벤트를_발행하지_않는다() {
    UUID paymentId = UUID.randomUUID();
    when(paymentRepository.tryTransitionFromPending(
            eq(paymentId), eq(Payment.Status.APPROVED), any(), any()))
        .thenReturn(0);

    Optional<Payment> result =
        finalizer.finalizePending(paymentId, Payment.Status.APPROVED, "tx-1", null);

    assertThat(result).isEmpty();
    verify(eventPublisher, never()).publish(any(), any(), any(), any());
    verify(paymentRepository, never()).findById(any());
  }

  @Test
  void 전이에_패배하면_실패이벤트도_발행하지_않는다() {
    UUID paymentId = UUID.randomUUID();
    when(paymentRepository.tryTransitionFromPending(
            eq(paymentId), eq(Payment.Status.FAILED), any(), any()))
        .thenReturn(0);

    Optional<Payment> result =
        finalizer.finalizePending(paymentId, Payment.Status.FAILED, null, "PG_REJECTED");

    assertThat(result).isEmpty();
    verify(eventPublisher, never()).publish(eq("PAYMENT"), any(), eq(FAILED_TOPIC), any());
  }

  @Test
  void PAYMENT_PENDING에서_전이할_수_없는_상태를_요청하면_예외가_발생한다() {
    UUID paymentId = UUID.randomUUID();

    assertThatThrownBy(
            () -> finalizer.finalizePending(paymentId, Payment.Status.REFUNDED, null, null))
        .isInstanceOf(IllegalStateException.class);

    verify(paymentRepository, never()).tryTransitionFromPending(any(), any(), any(), any());
  }
}
