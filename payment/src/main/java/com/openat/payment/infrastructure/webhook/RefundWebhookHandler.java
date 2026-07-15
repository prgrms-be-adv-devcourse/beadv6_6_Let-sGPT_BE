package com.openat.payment.infrastructure.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.service.RefundFinalizer;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// E2 — PaymentWebhookHandler와 동일한 모양(Day1 템플릿 재사용). 환불 PG 호출이 타임아웃돼 결과를 못 받은
// 경우를 잡는 보조 채널(환불 PG 호출은 원래도 동기 응답 구조라 A16 영향 없음).
// 토스 실제 웹훅 envelope에는 우리 자체 refundId가 없어(2026-06-24, research.md §17) Payment/WalletCharge와
// 동일하게 paymentKey로 Payment를 찾고, 그 Payment의 PENDING Refund를 역조회한다(plan.md P4).
// I1 — 페이로드의 status는 신뢰하지 않고 tossPaymentClient.queryRefundStatus 조회 결과로 최종 판정한다.
// 확정(한도원복 포함)은 RefundFinalizer로 위임(7-12 plan WS-D·E) — §4.1 환불 결함(lost-race에 한도원복)의 수정 지점.
@Slf4j
@Component
public class RefundWebhookHandler
    extends AbstractPgWebhookHandler<TossRefundWebhookPayload, Refund> {

  private final RefundRepository refundRepository;
  private final PaymentRepository paymentRepository;
  private final ObjectMapper objectMapper;
  private final TossPaymentClient tossPaymentClient;
  private final RefundFinalizer refundFinalizer;

  public RefundWebhookHandler(
      RefundRepository refundRepository,
      PaymentRepository paymentRepository,
      ObjectMapper objectMapper,
      TossPaymentClient tossPaymentClient,
      RefundFinalizer refundFinalizer) {
    this.refundRepository = refundRepository;
    this.paymentRepository = paymentRepository;
    this.objectMapper = objectMapper;
    this.tossPaymentClient = tossPaymentClient;
    this.refundFinalizer = refundFinalizer;
  }

  @Override
  protected TossRefundWebhookPayload parse(WebhookRequest request) {
    try {
      return objectMapper
          .readValue(request.getRawBody(), TossRefundWebhookPayload.Envelope.class)
          .data();
    } catch (Exception e) {
      log.error("[RefundWebhookHandler] 페이로드 파싱 실패: {}", request.getRawBody(), e);
      return null;
    }
  }

  @Override
  protected boolean checkIdempotency(TossRefundWebhookPayload payload) {
    return findPendingRefund(payload).isEmpty();
  }

  @Override
  protected WebhookUpdateResult<Refund> applyConditionalUpdate(TossRefundWebhookPayload payload) {
    Optional<Refund> maybeRefund = findPendingRefund(payload);
    if (maybeRefund.isEmpty()) {
      log.warn(
          "[RefundWebhookHandler] 매칭되는 PENDING Refund 없음: paymentKey={}", payload.paymentKey());
      return WebhookUpdateResult.noMatch();
    }
    Refund refund = maybeRefund.get();

    Optional<Payment> maybePayment = paymentRepository.findById(refund.getPaymentId());
    if (maybePayment.isEmpty()) {
      log.warn("[RefundWebhookHandler] 매칭되는 Payment 없음: paymentId={}", refund.getPaymentId());
      return WebhookUpdateResult.noMatch();
    }
    Payment payment = maybePayment.get();

    // I1 — 페이로드의 status는 트리거 신호로만 쓰고, 실제 판정은 PG 조회 결과로 한다.
    // pgRefundKey가 null(refundPayment 타임아웃 케이스)이면 amount로 매칭하는 폴백을 탄다(plan.md P3).
    TossQueryResult queryResult;
    try {
      queryResult =
          tossPaymentClient.queryRefundStatus(
              payment.getPgPaymentKey(), refund.getPgRefundKey(), refund.getAmount());
    } catch (Exception e) {
      log.warn("[RefundWebhookHandler] 웹훅 재검증 조회 실패, PENDING 유지: refundId={}", refund.getId(), e);
      return WebhookUpdateResult.unverifiable();
    }

    boolean approved = queryResult.status() == TossQueryResult.Status.APPROVED;

    // 하자드#10과 동일 원칙 — Finalizer의 조건부 UPDATE로 원자처리. fail이 이긴 경우에만 한도 원복(§4.1 결함 수정 지점).
    Optional<Refund> finalized =
        approved
            ? refundFinalizer.complete(refund.getId(), payment, queryResult.pgTxId())
            : refundFinalizer.fail(refund.getId(), payment, "PG_REJECTED");

    return finalized
        .map(
            updated ->
                approved
                    ? WebhookUpdateResult.approved(updated.getId(), updated)
                    : WebhookUpdateResult.<Refund>rejected(updated.getId(), updated))
        .orElseGet(() -> WebhookUpdateResult.lostRace(refund.getId()));
  }

  @Override
  protected void onApproved(Refund refund) {
    log.info("[RefundWebhookHandler] 웹훅으로 완료 확정: refundId={}", refund.getId());
  }

  @Override
  protected void onRejected(Refund refund) {
    log.info("[RefundWebhookHandler] 웹훅으로 거절 확정: refundId={}", refund.getId());
  }

  // paymentKey(웹훅) → Payment → 이 Payment의 PENDING Refund 역조회(plan.md P4). 2건 이상이면(가드 없음,
  // research.md §17.2) 가장 오래된(먼저 PENDING이 된) 것을 우선 처리하고 나머지는 경고만 남긴다.
  private Optional<Refund> findPendingRefund(TossRefundWebhookPayload payload) {
    Optional<Payment> payment = paymentRepository.findByPgPaymentKey(payload.paymentKey());
    if (payment.isEmpty()) {
      return Optional.empty();
    }
    List<Refund> pending =
        refundRepository.findByPaymentIdAndStatus(payment.get().getId(), Refund.Status.PENDING);
    if (pending.size() > 1) {
      log.warn(
          "[RefundWebhookHandler] 동일 Payment에 PENDING Refund가 {}건 — 가장 오래된 것을 처리: paymentId={}",
          pending.size(),
          payment.get().getId());
    }
    return pending.stream().min(Comparator.comparing(Refund::getCreatedAt));
  }
}
