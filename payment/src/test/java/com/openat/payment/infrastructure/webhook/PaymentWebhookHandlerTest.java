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
import com.openat.payment.application.service.PaymentFinalizer;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// 순수 Mockito 단위테스트(7-12 plan WS-A/E) — §4.1 결함(lost-race에 failed 이벤트 발행)의 재현·회귀방지 테스트.
// 확정(finalizePending) 자체는 PaymentFinalizer가 담당하므로(모킹), 이 테스트는 핸들러가 그 결과를
// LOST_RACE/APPROVED/REJECTED로 올바르게 매핑해 Template의 onApproved/onRejected 분기를 태우는지만 검증한다.
class PaymentWebhookHandlerTest {

  private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final TossPaymentClient tossPaymentClient = mock(TossPaymentClient.class);
  private final PaymentFinalizer paymentFinalizer = mock(PaymentFinalizer.class);

  private PaymentWebhookHandler handler;

  private final UUID paymentId = UUID.randomUUID();
  private final String paymentKey = "toss-payment-key";

  @BeforeEach
  void setUp() {
    handler =
        new PaymentWebhookHandler(
            paymentRepository, objectMapper, tossPaymentClient, paymentFinalizer);
  }

  @Test
  void 웹훅이_lost_race면_finalizer가_empty를_반환하고_핸들러는_추가_부수효과_없이_200을_반환한다() {
    Payment pending =
        Payment.builder().id(paymentId).status(Payment.Status.PAYMENT_PENDING).build();
    when(paymentRepository.findByPgPaymentKey(paymentKey)).thenReturn(Optional.of(pending));
    when(tossPaymentClient.queryPaymentStatus(paymentKey))
        .thenReturn(TossQueryResult.of(TossQueryResult.Status.APPROVED, "tx-1"));
    when(paymentFinalizer.finalizePending(paymentId, Payment.Status.APPROVED, "tx-1", null))
        .thenReturn(Optional.empty()); // 다른 경로(confirm/TTL)가 먼저 확정 — lost-race

    WebhookResult result = handler.handle(new WebhookRequest(body(paymentKey)));

    assertThat(result.getStatus().is2xxSuccessful()).isTrue();
    verify(paymentFinalizer).finalizePending(paymentId, Payment.Status.APPROVED, "tx-1", null);
  }

  @Test
  void 승인_조회_결과면_finalizer에_APPROVED_전이를_요청한다() {
    Payment pending =
        Payment.builder().id(paymentId).status(Payment.Status.PAYMENT_PENDING).build();
    Payment approved = Payment.builder().id(paymentId).status(Payment.Status.APPROVED).build();
    when(paymentRepository.findByPgPaymentKey(paymentKey)).thenReturn(Optional.of(pending));
    when(tossPaymentClient.queryPaymentStatus(paymentKey))
        .thenReturn(TossQueryResult.of(TossQueryResult.Status.APPROVED, "tx-1"));
    when(paymentFinalizer.finalizePending(paymentId, Payment.Status.APPROVED, "tx-1", null))
        .thenReturn(Optional.of(approved));

    handler.handle(new WebhookRequest(body(paymentKey)));

    verify(paymentFinalizer).finalizePending(paymentId, Payment.Status.APPROVED, "tx-1", null);
  }

  @Test
  void 거절_조회_결과면_finalizer에_FAILED_전이와_PG_REJECTED_사유를_요청한다() {
    Payment pending =
        Payment.builder().id(paymentId).status(Payment.Status.PAYMENT_PENDING).build();
    Payment failed = Payment.builder().id(paymentId).status(Payment.Status.FAILED).build();
    when(paymentRepository.findByPgPaymentKey(paymentKey)).thenReturn(Optional.of(pending));
    when(tossPaymentClient.queryPaymentStatus(paymentKey))
        .thenReturn(TossQueryResult.of(TossQueryResult.Status.FAILED, "tx-1"));
    when(paymentFinalizer.finalizePending(paymentId, Payment.Status.FAILED, "tx-1", "PG_REJECTED"))
        .thenReturn(Optional.of(failed));

    handler.handle(new WebhookRequest(body(paymentKey)));

    verify(paymentFinalizer)
        .finalizePending(paymentId, Payment.Status.FAILED, "tx-1", "PG_REJECTED");
  }

  @Test
  void 매칭되는_Payment가_없으면_finalizer를_호출하지_않는다() {
    when(paymentRepository.findByPgPaymentKey(paymentKey)).thenReturn(Optional.empty());

    WebhookResult result = handler.handle(new WebhookRequest(body(paymentKey)));

    assertThat(result.getStatus().is2xxSuccessful()).isTrue();
    verify(paymentFinalizer, never()).finalizePending(any(), any(), any(), any());
  }

  @Test
  void PG_조회가_실패하면_PENDING_유지하고_finalizer를_호출하지_않는다() {
    Payment pending =
        Payment.builder().id(paymentId).status(Payment.Status.PAYMENT_PENDING).build();
    when(paymentRepository.findByPgPaymentKey(paymentKey)).thenReturn(Optional.of(pending));
    when(tossPaymentClient.queryPaymentStatus(paymentKey))
        .thenThrow(new RuntimeException("timeout"));

    WebhookResult result = handler.handle(new WebhookRequest(body(paymentKey)));

    assertThat(result.getStatus().is2xxSuccessful()).isTrue();
    verify(paymentFinalizer, never()).finalizePending(any(), any(), any(), any());
  }

  @Test
  void 이미_PENDING을_벗어난_Payment면_재처리하지_않는다() {
    Payment approved = Payment.builder().id(paymentId).status(Payment.Status.APPROVED).build();
    when(paymentRepository.findByPgPaymentKey(paymentKey)).thenReturn(Optional.of(approved));

    WebhookResult result = handler.handle(new WebhookRequest(body(paymentKey)));

    assertThat(result.getStatus().is2xxSuccessful()).isTrue();
    verifyNoMoreQueryOrFinalize();
  }

  private void verifyNoMoreQueryOrFinalize() {
    verify(tossPaymentClient, never()).queryPaymentStatus(any());
    verify(paymentFinalizer, never()).finalizePending(any(), any(), any(), any());
  }

  private String body(String paymentKey) {
    return "{\"data\":{\"paymentKey\":\"" + paymentKey + "\"}}";
  }
}
