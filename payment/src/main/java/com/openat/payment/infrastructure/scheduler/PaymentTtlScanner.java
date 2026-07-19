package com.openat.payment.infrastructure.scheduler;

import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.application.service.PaymentFinalizer;
import com.openat.payment.application.service.RefundFinalizer;
import com.openat.payment.application.service.WalletChargeFinalizer;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.model.Refund;
import com.openat.payment.domain.model.WalletCharge;
import com.openat.payment.domain.repository.PaymentRepository;
import com.openat.payment.domain.repository.RefundRepository;
import com.openat.payment.domain.repository.WalletChargeRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
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
  private final RefundRepository refundRepository;
  private final TossPaymentClient tossPaymentClient;
  private final PaymentFinalizer finalizer;
  private final WalletChargeFinalizer chargeFinalizer;
  private final RefundFinalizer refundFinalizer;
  private final Counter expiredCounter;

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
      RefundRepository refundRepository,
      TossPaymentClient tossPaymentClient,
      PaymentFinalizer finalizer,
      WalletChargeFinalizer chargeFinalizer,
      RefundFinalizer refundFinalizer,
      MeterRegistry meterRegistry) {
    this.paymentRepository = paymentRepository;
    this.walletChargeRepository = walletChargeRepository;
    this.refundRepository = refundRepository;
    this.tossPaymentClient = tossPaymentClient;
    this.finalizer = finalizer;
    this.chargeFinalizer = chargeFinalizer;
    this.refundFinalizer = refundFinalizer;
    this.expiredCounter = meterRegistry.counter("payment.ttl.expired");
  }

  // 멀티 인스턴스 동시 실행 안전성: 여러 인스턴스가 같은 stale row를 동시에 집어도 중복 조회는 무해하고(PG 조회는 멱등),
  // 종결은 전부 상태 조건부 UPDATE(WHERE status='PENDING'/'PAYMENT_PENDING')라 한쪽만 이기고 나머지는 0-row로 무시돼
  // 정합성이 유지된다(complete/fail/finalizePending 모두 동일 구조). fail의 환불액 원복도 전이에 이긴 경우에만 수행되므로
  // 이중 원복이 없다. 지금은 replicas=1이라 실경합이 없고, 스케일아웃 시점에 중복 조회 낭비를 없애려면 ShedLock을 이 지점에 도입.
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

    // 환불 PG 호출이 타임아웃(UNKNOWN)돼 PENDING으로 굳은 건도 같은 주기로 회수. 환불은 항상 PG 결제 대상이라
    // (WALLET 환불은 접수 TX에서 즉시 완료됨) pgPaymentKey가 있어 재조회로만 판정 — Payment의 handleWithKey와 동형.
    List<Refund> staleRefunds = refundRepository.findStalePending(threshold);
    for (Refund refund : staleRefunds) {
      try {
        handleStaleRefund(refund);
      } catch (Exception e) {
        log.error(
            "[PaymentTtlScanner] Refund 처리 실패, 다음 주기에 재시도: refundId={}", refund.getId(), e);
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
    expiredCounter.increment();
    finalizer.finalizePending(payment.getId(), Payment.Status.FAILED, null, "EXPIRED");
  }

  private void handleChargeMissingKey(WalletCharge charge) {
    LocalDateTime graceThreshold =
        LocalDateTime.now().minusMinutes(pendingTimeoutMinutes + nullKeyGraceMinutes);
    if (charge.getCreatedAt().isAfter(graceThreshold)) {
      return;
    }
    expiredCounter.increment();
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
      expiredCounter.increment();
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
      expiredCounter.increment();
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
      case NOT_FOUND -> {
        expiredCounter.increment();
        finalizer.finalizePending(
            payment.getId(), Payment.Status.FAILED, result.pgTxId(), "EXPIRED");
      }
    }
  }

  // 환불 PG 응답을 못 받아 굳은 PENDING Refund 회수 — 마지노선 도달 전엔 조회하지 않고(created_at 기준),
  // 도달하면 토스에 1회 조회해 확정한다. RefundWebhookHandler.applyConditionalUpdate와 동일한 판정 로직.
  private void handleStaleRefund(Refund refund) {
    LocalDateTime deadlineThreshold = LocalDateTime.now().minusMinutes(finalizeDeadlineMinutes);
    if (refund.getCreatedAt().isAfter(deadlineThreshold)) {
      return; // 마지노선 전 — 조회하지 않음, 다음 주기에 재확인(그 사이 보조 웹훅이 확정할 수도 있다).
    }
    Payment payment = paymentRepository.findById(refund.getPaymentId()).orElse(null);
    if (payment == null) {
      log.warn("[PaymentTtlScanner] Refund의 Payment 없음, 다음 주기에 재시도: refundId={}", refund.getId());
      return;
    }
    TossQueryResult result;
    try {
      // pgRefundKey가 null(refundPayment 타임아웃)이면 amount 폴백으로 매칭(RefundWebhookHandler와 동일).
      result =
          tossPaymentClient.queryRefundStatus(
              payment.getPgPaymentKey(), refund.getPgRefundKey(), refund.getAmount());
    } catch (Exception e) {
      // Payment/충전과 달리 강제 종결하지 않는다 — 환불을 임의 FAILED로 닫으면 환불액 원복이 일어나는데, 실제로는
      // PG에서 환불이 성사됐을 수 있어 이중 환불 창이 열린다. PENDING 유지 후 다음 주기·야간 대사에 위임.
      log.warn("[PaymentTtlScanner] 환불 재조회 실패, PENDING 유지: refundId={}", refund.getId(), e);
      return;
    }
    switch (result.status()) {
      case APPROVED -> refundFinalizer.complete(refund.getId(), payment, result.pgTxId());
      case FAILED ->
          // PG가 환불을 명시적으로 거절했음. fail이 환불액 원복(tryDecreaseRefundedAmount)까지 수행.
          refundFinalizer.fail(refund.getId(), payment, "PG_REJECTED");
      case NOT_FOUND ->
          // NOT_FOUND는 '확정 실패'가 아니라 '조회 불확실'(pgRefundKey null로 amount 폴백 매칭이 빗나갔거나 PG 반영 지연).
          // 여기서 fail로 강제 종결하면 환불액 원복이 일어나는데 실제로 PG 환불이 성사돼 있으면 이중 환불 창이 열린다 —
          // 위 조회 실패 catch 절과 동일 원칙(불확실은 강제 종결하지 않는다). PENDING 유지 후 다음 주기·야간 대사에 위임.
          log.warn(
              "[PaymentTtlScanner] 환불 조회 불확실(NOT_FOUND), PENDING 유지 — 야간 대사에 위임: refundId={}",
              refund.getId());
    }
  }
}
