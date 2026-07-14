package com.openat.payment.infrastructure.scheduler;

import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.service.PaymentFinalizer;
import com.openat.payment.application.service.WalletChargeFinalizer;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.WalletCharge;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.WalletChargeRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// confirm 미호출 보정(§3, A16 이후 역할 정정) — PG confirm이 메인 경로가 되면서, 브라우저가 결제창에서
// 이탈하거나 confirm 호출 도중 끊기면 PAYMENT_PENDING이 영원히 안 닫힐 수 있다(하자드#21). 이 스캐너가 그 row를
// 주기적으로 회수해서 강제 확정한다 — 정상 confirm 경로에는 끼지 않는 별도 보정 채널.
// 확정은 application/service의 PaymentFinalizer/WalletChargeFinalizer로 위임(7-12 plan WS-D — confirm
// 동기응답/
// 보조웹훅/TTL스캐너 3경로가 전부 같은 Finalizer를 공유).
@Slf4j
@Component
public class PaymentTtlScanner {

  private final PaymentRepository paymentRepository;
  private final WalletChargeRepository walletChargeRepository;
  private final TossPaymentClient tossPaymentClient;
  private final PaymentFinalizer finalizer;
  private final WalletChargeFinalizer chargeFinalizer;

  // N분 — pgPaymentKey가 NULL인 row(handleMissingKey) 기준. 운영 기본값 10분, 시연/테스트 시 application-local.yml에서
  // 짧게 오버라이드.
  @Value("${payment.ttl-scanner.pending-timeout-minutes:10}")
  private long pendingTimeoutMinutes;

  // M분(추가 그레이스) — pgPaymentKey가 NULL인 row만 적용.
  // confirm 자체를 호출한 적이 없으면 PG에 물어볼 키가 없어 시간 기반으로만 확정할 수 있어 더 신중하게 기다린다.
  // 7-12 plan WS-G — 주문 TTL(10분) 이내 확정 보장(Q49 차이 D 반영) 위해 기본값을 5→0으로 단축.
  @Value("${payment.ttl-scanner.null-key-grace-minutes:0}")
  private long nullKeyGraceMinutes;

  // D9(Q101 확정) — 조기 폴링 없이 마지노선 하나로 단발화. 이 시간이 지난 row만 PG에 1회 조회한다.
  // 결정적 응답이면 즉시 확정, 조회 자체가 실패(예외/타임아웃)하면 재시도·유예 없이 즉시 FAILED(FORCED_TIMEOUT).
  @Value("${payment.ttl-scanner.finalize-deadline-minutes:8}")
  private long finalizeDeadlineMinutes;

  public PaymentTtlScanner(
      PaymentRepository paymentRepository,
      WalletChargeRepository walletChargeRepository,
      TossPaymentClient tossPaymentClient,
      PaymentFinalizer finalizer,
      WalletChargeFinalizer chargeFinalizer) {
    this.paymentRepository = paymentRepository;
    this.walletChargeRepository = walletChargeRepository;
    this.tossPaymentClient = tossPaymentClient;
    this.finalizer = finalizer;
    this.chargeFinalizer = chargeFinalizer;
  }

  @Scheduled(fixedDelay = 60_000)
  public void scan() {
    // D9 — 키 있는 row(마지노선)/키 없는 row(pending-timeout) 중 더 이른 임계값으로 한 번에 가져온 뒤,
    // 각 핸들러가 자기 기준(마지노선 vs pending-timeout+grace)으로 재판단한다.
    long fetchThresholdMinutes = Math.min(finalizeDeadlineMinutes, pendingTimeoutMinutes);
    LocalDateTime threshold = LocalDateTime.now().minusMinutes(fetchThresholdMinutes);

    List<Payment> stale = paymentRepository.findStalePending(threshold);
    for (Payment payment : stale) {
      try {
        if (payment.getPgPaymentKey() == null) {
          handleMissingKey(payment);
        } else {
          handleWithKey(payment);
        }
      } catch (Exception e) {
        log.error("[PaymentTtlScanner] 처리 실패, 다음 주기에 재시도: paymentId={}", payment.getId(), e);
      }
    }

    // §5 하자드#10 — WalletCharge(PENDING)도 동일한 주기로 스캔. Payment와 동일한 3단계 로직 적용.
    List<WalletCharge> staleCharges = walletChargeRepository.findStalePending(threshold);
    for (WalletCharge charge : staleCharges) {
      try {
        if (charge.getPgPaymentKey() == null) {
          handleChargeMissingKey(charge);
        } else {
          handleChargeWithKey(charge);
        }
      } catch (Exception e) {
        log.error(
            "[PaymentTtlScanner] WalletCharge 처리 실패, 다음 주기에 재시도: chargeId={}", charge.getId(), e);
      }
    }
  }

  // confirm을 한 번도 호출한 적이 없는 row(결제창 이탈/포기) — PG에 물어볼 키가 없으므로 시간만으로 확정.
  private void handleMissingKey(Payment payment) {
    LocalDateTime graceThreshold =
        LocalDateTime.now().minusMinutes(pendingTimeoutMinutes + nullKeyGraceMinutes);
    if (payment.getCreatedAt().isAfter(graceThreshold)) {
      return; // 그레이스 기간 안 지남 — 다음 회차에 재시도
    }
    finalizer.finalizePending(payment.getId(), Payment.Status.FAILED, null, "EXPIRED");
  }

  private void handleChargeMissingKey(WalletCharge charge) {
    LocalDateTime graceThreshold =
        LocalDateTime.now().minusMinutes(pendingTimeoutMinutes + nullKeyGraceMinutes);
    if (charge.getCreatedAt().isAfter(graceThreshold)) {
      return;
    }
    chargeFinalizer.finalizePending(charge.getId(), WalletCharge.Status.FAILED, null);
  }

  private void handleChargeWithKey(WalletCharge charge) {
    LocalDateTime deadlineThreshold = LocalDateTime.now().minusMinutes(finalizeDeadlineMinutes);
    if (charge.getCreatedAt().isAfter(deadlineThreshold)) {
      return; // 마지노선 전 — 조회하지 않음, 다음 주기에 재확인(D9)
    }
    TossQueryResult result;
    try {
      result = tossPaymentClient.queryPaymentStatus(charge.getPgPaymentKey());
    } catch (Exception e) {
      // D9 — 조회 실패는 재시도·유예 없이 즉시 강제 종결.
      log.error(
          "[PaymentTtlScanner] WalletCharge {}분 경과, PG 조회 실패로 강제 종결: chargeId={}",
          finalizeDeadlineMinutes,
          charge.getId(),
          e);
      chargeFinalizer.finalizePending(charge.getId(), WalletCharge.Status.FAILED, null);
      return;
    }
    switch (result.status()) {
      case APPROVED ->
          chargeFinalizer.finalizePending(
              charge.getId(), WalletCharge.Status.APPROVED, result.pgTxId());
      case FAILED, NOT_FOUND ->
          chargeFinalizer.finalizePending(
              charge.getId(), WalletCharge.Status.FAILED, result.pgTxId());
    }
  }

  // 신-하자드9 — confirm이 PG호출까지는 갔는데 우리 쪽 조건부 UPDATE가 끊긴 좁은 케이스. 키로 PG에 직접 물어 회복.
  // D9 — 마지노선 도달 전엔 조회하지 않고(건별 타이머 없이 created_at 기준), 도달하면 1회 조회 후 재시도 없이 종결.
  private void handleWithKey(Payment payment) {
    LocalDateTime deadlineThreshold = LocalDateTime.now().minusMinutes(finalizeDeadlineMinutes);
    if (payment.getCreatedAt().isAfter(deadlineThreshold)) {
      return; // 마지노선 전 — 조회하지 않음, 다음 주기에 재확인
    }
    TossQueryResult result;
    try {
      result = tossPaymentClient.queryPaymentStatus(payment.getPgPaymentKey());
    } catch (Exception e) {
      // D9 — 조회 실패는 재시도·유예 없이 즉시 강제 종결(백스톱은 7/15 대사배치).
      log.error(
          "[PaymentTtlScanner] {}분 경과, PG 조회 실패로 강제 종결: paymentId={}",
          finalizeDeadlineMinutes,
          payment.getId(),
          e);
      finalizer.finalizePending(payment.getId(), Payment.Status.FAILED, null, "FORCED_TIMEOUT");
      return;
    }
    switch (result.status()) {
      case APPROVED ->
          finalizer.finalizePending(
              payment.getId(), Payment.Status.APPROVED, result.pgTxId(), null);
      case FAILED ->
          finalizer.finalizePending(
              payment.getId(), Payment.Status.FAILED, result.pgTxId(), "PG_REJECTED");
      case NOT_FOUND ->
          finalizer.finalizePending(
              payment.getId(), Payment.Status.FAILED, result.pgTxId(), "EXPIRED");
    }
  }
}
