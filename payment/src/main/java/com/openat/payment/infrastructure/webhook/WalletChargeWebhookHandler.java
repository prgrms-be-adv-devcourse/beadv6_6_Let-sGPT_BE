package com.openat.payment.infrastructure.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.service.WalletChargeFinalizer;
import com.openat.payment.domain.model.WalletCharge;
import com.openat.payment.domain.repository.WalletChargeRepository;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// E1 — PaymentWebhookHandler와 동일한 모양(Day1 템플릿 재사용). 충전 confirm이 누락된 건을 잡는 보조 채널.
// confirm 메인 구조라 이벤트 발행은 없음(api_event_specification.md상 충전 도메인엔 Kafka 발행 항목 자체가 없음) —
// 대신 confirmCharge와 동일하게 승인 시 Wallet 잔액 반영까지가 이 핸들러의 후속처리.
// I1 — 페이로드의 status는 신뢰하지 않고 tossPaymentClient.queryPaymentStatus 조회 결과로 최종 판정한다.
// 지갑 반영은 WalletChargeFinalizer로 위임(7-12 plan WS-D·E, §4.2 결함 수정 지점).
@Slf4j
@Component
public class WalletChargeWebhookHandler
    extends AbstractPgWebhookHandler<TossWalletChargeWebhookPayload, WalletCharge> {

  private final WalletChargeRepository walletChargeRepository;
  private final ObjectMapper objectMapper;
  private final TossPaymentClient tossPaymentClient;
  private final WalletChargeFinalizer walletChargeFinalizer;

  public WalletChargeWebhookHandler(
      WalletChargeRepository walletChargeRepository,
      ObjectMapper objectMapper,
      TossPaymentClient tossPaymentClient,
      WalletChargeFinalizer walletChargeFinalizer) {
    this.walletChargeRepository = walletChargeRepository;
    this.objectMapper = objectMapper;
    this.tossPaymentClient = tossPaymentClient;
    this.walletChargeFinalizer = walletChargeFinalizer;
  }

  @Override
  protected TossWalletChargeWebhookPayload parse(WebhookRequest request) {
    try {
      return objectMapper
          .readValue(request.getRawBody(), TossWalletChargeWebhookPayload.Envelope.class)
          .data();
    } catch (Exception e) {
      log.error("[WalletChargeWebhookHandler] 페이로드 파싱 실패: {}", request.getRawBody(), e);
      return null;
    }
  }

  @Override
  protected boolean checkIdempotency(TossWalletChargeWebhookPayload payload) {
    Optional<WalletCharge> charge = walletChargeRepository.findByPgPaymentKey(payload.paymentKey());
    return charge.isPresent() && charge.get().getStatus() != WalletCharge.Status.PENDING;
  }

  @Override
  protected WebhookUpdateResult<WalletCharge> applyConditionalUpdate(
      TossWalletChargeWebhookPayload payload) {
    Optional<WalletCharge> maybeCharge =
        walletChargeRepository.findByPgPaymentKey(payload.paymentKey());
    if (maybeCharge.isEmpty()) {
      log.warn(
          "[WalletChargeWebhookHandler] 매칭되는 WalletCharge 없음: paymentKey={}", payload.paymentKey());
      return WebhookUpdateResult.noMatch();
    }
    WalletCharge charge = maybeCharge.get();

    // I1 — 페이로드의 status는 트리거 신호로만 쓰고, 실제 판정은 PG 조회 결과로 한다.
    TossQueryResult queryResult;
    try {
      queryResult = tossPaymentClient.queryPaymentStatus(payload.paymentKey());
    } catch (Exception e) {
      log.warn(
          "[WalletChargeWebhookHandler] 웹훅 재검증 조회 실패, PENDING 유지: chargeId={}", charge.getId(), e);
      return WebhookUpdateResult.unverifiable();
    }

    WalletCharge.Status newStatus =
        queryResult.status() == TossQueryResult.Status.APPROVED
            ? WalletCharge.Status.APPROVED
            : WalletCharge.Status.FAILED;

    return walletChargeFinalizer
        .finalizePending(charge.getId(), newStatus, queryResult.pgTxId())
        .map(
            updated ->
                newStatus == WalletCharge.Status.APPROVED
                    ? WebhookUpdateResult.approved(updated.getId(), updated)
                    : WebhookUpdateResult.<WalletCharge>rejected(updated.getId(), updated))
        .orElseGet(() -> WebhookUpdateResult.lostRace(charge.getId()));
  }

  @Override
  protected void onApproved(WalletCharge charge) {
    log.info("[WalletChargeWebhookHandler] 웹훅으로 승인 확정: chargeId={}", charge.getId());
  }

  @Override
  protected void onRejected(WalletCharge charge) {
    // 이벤트 발행 없음(api_event_specification.md상 충전 도메인엔 Kafka 발행 항목 자체가 없음) — 상태 전이로 끝.
  }
}
