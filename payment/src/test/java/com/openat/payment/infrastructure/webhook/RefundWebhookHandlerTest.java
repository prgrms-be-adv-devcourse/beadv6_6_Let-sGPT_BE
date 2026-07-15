package com.openat.payment.infrastructure.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.service.RefundFinalizer;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 순수 Mockito 단위테스트(7-12 plan WS-A/E) — §4.1 환불 결함(lost-race에 환불한도 원복)의 재현·회귀방지 테스트.
// 한도원복 자체는 RefundFinalizer.fail이 담당하므로(모킹) 여기서는 핸들러가 finalizer.complete/fail 중
// 올바른 쪽에 올바른 인자로 위임하는지만 검증한다 — 한도원복이 lost-race에서 실행되지 않음은 RefundFinalizerTest가 증명.
class RefundWebhookHandlerTest {

  private final RefundRepository refundRepository = mock(RefundRepository.class);
  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TossPaymentClient tossPaymentClient = mock(TossPaymentClient.class);
  private final RefundFinalizer refundFinalizer = mock(RefundFinalizer.class);

  private RefundWebhookHandler handler;

  private final UUID paymentId = UUID.randomUUID();
  private final UUID refundId = UUID.randomUUID();
  private final String paymentKey = "toss-payment-key";
  private Payment payment;
  private Refund pendingRefund;

  @BeforeEach
  void setUp() {
    handler =
        new RefundWebhookHandler(
            refundRepository, paymentRepository, objectMapper, tossPaymentClient, refundFinalizer);

    payment = Payment.builder().id(paymentId).pgPaymentKey(paymentKey).build();
    pendingRefund =
        Refund.builder()
            .id(refundId)
            .paymentId(paymentId)
            .amount(3_000L)
            .status(Refund.Status.PENDING)
            .createdAt(LocalDateTime.now())
            .build();

    when(paymentRepository.findByPgPaymentKey(paymentKey)).thenReturn(Optional.of(payment));
    when(paymentRepository.findById(paymentId)).thenReturn(Optional.of(payment));
    when(refundRepository.findByPaymentIdAndStatus(paymentId, Refund.Status.PENDING))
        .thenReturn(List.of(pendingRefund));
  }

  @Test
  void 환불웹훅이_lost_race면_finalizer_fail이_empty를_반환하고_핸들러는_추가_부수효과_없이_200을_반환한다() {
    when(tossPaymentClient.queryRefundStatus(paymentKey, null, 3_000L))
        .thenReturn(TossQueryResult.of(TossQueryResult.Status.FAILED, "tx-1"));
    when(refundFinalizer.fail(refundId, payment, "PG_REJECTED")).thenReturn(Optional.empty());

    WebhookResult result = handler.handle(new WebhookRequest(body(paymentKey)));

    assertThat(result.getStatus().is2xxSuccessful()).isTrue();
    verify(refundFinalizer).fail(refundId, payment, "PG_REJECTED");
    verify(refundFinalizer, never()).complete(any(), any(), any());
  }

  @Test
  void 환불웹훅이_정식_거절이면_finalizer_fail에_위임한다() {
    when(tossPaymentClient.queryRefundStatus(paymentKey, null, 3_000L))
        .thenReturn(TossQueryResult.of(TossQueryResult.Status.FAILED, "tx-1"));
    Refund failed =
        Refund.builder()
            .id(refundId)
            .paymentId(paymentId)
            .amount(3_000L)
            .status(Refund.Status.FAILED)
            .build();
    when(refundFinalizer.fail(refundId, payment, "PG_REJECTED")).thenReturn(Optional.of(failed));

    WebhookResult result = handler.handle(new WebhookRequest(body(paymentKey)));

    assertThat(result.getStatus().is2xxSuccessful()).isTrue();
    verify(refundFinalizer).fail(refundId, payment, "PG_REJECTED");
  }

  @Test
  void 환불웹훅이_승인이면_finalizer_complete에_위임한다() {
    when(tossPaymentClient.queryRefundStatus(paymentKey, null, 3_000L))
        .thenReturn(TossQueryResult.of(TossQueryResult.Status.APPROVED, "pg-refund-1"));
    Refund completed =
        Refund.builder()
            .id(refundId)
            .paymentId(paymentId)
            .amount(3_000L)
            .status(Refund.Status.COMPLETE)
            .build();
    when(refundFinalizer.complete(refundId, payment, "pg-refund-1"))
        .thenReturn(Optional.of(completed));

    handler.handle(new WebhookRequest(body(paymentKey)));

    verify(refundFinalizer).complete(refundId, payment, "pg-refund-1");
    verify(refundFinalizer, never()).fail(any(), any(), any());
  }

  @Test
  void 매칭되는_PENDING_Refund가_없으면_finalizer를_호출하지_않는다() {
    when(refundRepository.findByPaymentIdAndStatus(paymentId, Refund.Status.PENDING))
        .thenReturn(List.of());

    WebhookResult result = handler.handle(new WebhookRequest(body(paymentKey)));

    assertThat(result.getStatus().is2xxSuccessful()).isTrue();
    verify(refundFinalizer, never()).complete(any(), any(), any());
    verify(refundFinalizer, never()).fail(any(), any(), any());
  }

  private String body(String paymentKey) {
    return "{\"data\":{\"paymentKey\":\"" + paymentKey + "\"}}";
  }
}
