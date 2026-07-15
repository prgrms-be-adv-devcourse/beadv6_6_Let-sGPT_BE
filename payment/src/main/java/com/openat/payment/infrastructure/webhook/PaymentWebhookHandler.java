package com.openat.payment.infrastructure.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.service.PaymentFinalizer;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// Day1 템플릿(AbstractPgWebhookHandler)의 첫 구체 구현 — payment_domain_diagrams.md §2.
// 토스 실제 웹훅 envelope은 아직 미확정(qna.md 논의 보류) — 우리 쪽 계약(api_event_specification.md)대로 우선 구현,
// ngrok 실연동 시 페이로드 파싱 부분만 교체하면 됨.
// I1 — 페이로드의 status는 신뢰하지 않고 tossPaymentClient.queryPaymentStatus 조회 결과로 최종 판정한다.
// 확정(부수효과)은 PaymentFinalizer로 위임(7-12 plan WS-D·E) — onApproved/onRejected는 로그만 남긴다.
@Slf4j
@Component
public class PaymentWebhookHandler
    extends AbstractPgWebhookHandler<TossPaymentWebhookPayload, Payment> {

  private final PaymentRepository paymentRepository;
  private final ObjectMapper objectMapper;
  private final TossPaymentClient tossPaymentClient;
  private final PaymentFinalizer paymentFinalizer;

  public PaymentWebhookHandler(
      PaymentRepository paymentRepository,
      ObjectMapper objectMapper,
      TossPaymentClient tossPaymentClient,
      PaymentFinalizer paymentFinalizer) {
    this.paymentRepository = paymentRepository;
    this.objectMapper = objectMapper;
    this.tossPaymentClient = tossPaymentClient;
    this.paymentFinalizer = paymentFinalizer;
  }

  @Override
  protected TossPaymentWebhookPayload parse(WebhookRequest request) {
    try {
      return objectMapper
          .readValue(request.getRawBody(), TossPaymentWebhookPayload.Envelope.class)
          .data();
    } catch (Exception e) {
      log.error("[PaymentWebhookHandler] 페이로드 파싱 실패: {}", request.getRawBody(), e);
      return null;
    }
  }

  @Override
  protected boolean checkIdempotency(TossPaymentWebhookPayload payload) {
    Optional<Payment> payment = paymentRepository.findByPgPaymentKey(payload.paymentKey());
    // 이미 PAYMENT_PENDING을 벗어난 row면 재전송(§4 토스 재전송 정책)으로 판단 — 재처리하지 않음.
    return payment.isPresent() && payment.get().getStatus() != Payment.Status.PAYMENT_PENDING;
  }

  @Override
  protected WebhookUpdateResult<Payment> applyConditionalUpdate(TossPaymentWebhookPayload payload) {
    Optional<Payment> maybePayment = paymentRepository.findByPgPaymentKey(payload.paymentKey());
    if (maybePayment.isEmpty()) {
      // 하자드#3 — 웹훅이 PENDING row보다 먼저 도착한 극단 케이스. 세미 범위 밖(research.md §10.3 #3) — 무시.
      log.warn("[PaymentWebhookHandler] 매칭되는 Payment 없음: paymentKey={}", payload.paymentKey());
      return WebhookUpdateResult.noMatch();
    }
    Payment payment = maybePayment.get();

    // I1 — 페이로드의 status는 "지금 뭔가 바뀌었다"는 트리거 신호로만 쓰고, 실제 판정은 PG 조회 결과로 한다.
    TossQueryResult queryResult;
    try {
      queryResult = tossPaymentClient.queryPaymentStatus(payload.paymentKey());
    } catch (Exception e) {
      // 조회 실패(타임아웃/네트워크 오류) — 강제로 닫지 않고 PENDING 유지, TTL스캐너 다음 주기 재시도에 위임.
      log.warn(
          "[PaymentWebhookHandler] 웹훅 재검증 조회 실패, PENDING 유지: paymentId={}", payment.getId(), e);
      return WebhookUpdateResult.unverifiable();
    }

    Payment.Status newStatus =
        queryResult.status() == TossQueryResult.Status.APPROVED
            ? Payment.Status.APPROVED
            : Payment.Status.FAILED;
    String reason = newStatus == Payment.Status.APPROVED ? null : "PG_REJECTED";

    // 하자드#10 — Finalizer의 조건부 UPDATE로 원자처리. lost-race면 부수효과 없이 그대로 무시(§4.1 결함 수정 지점).
    return paymentFinalizer
        .finalizePending(payment.getId(), newStatus, queryResult.pgTxId(), reason)
        .map(
            updated ->
                newStatus == Payment.Status.APPROVED
                    ? WebhookUpdateResult.approved(updated.getId(), updated)
                    : WebhookUpdateResult.<Payment>rejected(updated.getId(), updated))
        .orElseGet(() -> WebhookUpdateResult.lostRace(payment.getId()));
  }

  @Override
  protected void onApproved(Payment payment) {
    log.info("[PaymentWebhookHandler] 웹훅으로 승인 확정: paymentId={}", payment.getId());
  }

  @Override
  protected void onRejected(Payment payment) {
    log.info("[PaymentWebhookHandler] 웹훅으로 거절 확정: paymentId={}", payment.getId());
  }
}
