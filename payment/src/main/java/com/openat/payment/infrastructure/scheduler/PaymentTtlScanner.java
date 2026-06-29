package com.openat.payment.infrastructure.scheduler;

import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossQueryResult;
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
@Slf4j
@Component
public class PaymentTtlScanner {

    private final PaymentRepository paymentRepository;
    private final WalletChargeRepository walletChargeRepository;
    private final TossPaymentClient tossPaymentClient;
    private final PaymentTtlFinalizer finalizer;
    private final WalletChargeTtlFinalizer chargeFinalizer;

    // N분 — pgPaymentKey가 NULL인 row(handleMissingKey) 기준. 운영 기본값 10분, 시연/테스트 시 application-local.yml에서 짧게 오버라이드.
    @Value("${payment.ttl-scanner.pending-timeout-minutes:10}")
    private long pendingTimeoutMinutes;

    // M분(추가 그레이스) — pgPaymentKey가 NULL인 row만 적용.
    // confirm 자체를 호출한 적이 없으면 PG에 물어볼 키가 없어 시간 기반으로만 확정할 수 있어 더 신중하게 기다린다.
    @Value("${payment.ttl-scanner.null-key-grace-minutes:5}")
    private long nullKeyGraceMinutes;

    // I2 — pgPaymentKey가 있는 row는 이 시간부터 매 스캔 주기(1분)마다 PG 선제 조회를 시작한다(10분보다 이른 5분).
    @Value("${payment.ttl-scanner.with-key-attempt-minutes:5}")
    private long withKeyAttemptMinutes;

    // I2-3 — 이 시간을 넘겼는데도 PG 조회 호출 자체가 매번 실패(예외/타임아웃)해서 결정적 응답을 못 받은 row만
    // FAILED(FORCED_TIMEOUT)로 강제 종결한다. 조회가 정상 응답(APPROVED/FAILED/NOT_FOUND)을 준 row는 이 시각과
    // 무관하게 그 즉시 확정되므로(handleWithKey), "TTL이 PG의 진짜 결과보다 먼저 확정"하는 위험은 없다.
    @Value("${payment.ttl-scanner.final-timeout-minutes:8}")
    private long finalTimeoutMinutes;

    public PaymentTtlScanner(PaymentRepository paymentRepository, WalletChargeRepository walletChargeRepository,
            TossPaymentClient tossPaymentClient, PaymentTtlFinalizer finalizer,
            WalletChargeTtlFinalizer chargeFinalizer) {
        this.paymentRepository = paymentRepository;
        this.walletChargeRepository = walletChargeRepository;
        this.tossPaymentClient = tossPaymentClient;
        this.finalizer = finalizer;
        this.chargeFinalizer = chargeFinalizer;
    }

    @Scheduled(fixedDelay = 60_000)
    public void scan() {
        // I2 — 키 있는 row(5분)/키 없는 row(10분) 중 더 이른 임계값으로 한 번에 가져온 뒤, 각 핸들러가 자기 기준으로 재판단.
        long fetchThresholdMinutes = Math.min(withKeyAttemptMinutes, pendingTimeoutMinutes);
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
                log.error("[PaymentTtlScanner] WalletCharge 처리 실패, 다음 주기에 재시도: chargeId={}", charge.getId(), e);
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
        finalizer.finalizePending(payment, Payment.Status.FAILED, null, "EXPIRED");
    }

    private void handleChargeMissingKey(WalletCharge charge) {
        LocalDateTime graceThreshold =
                LocalDateTime.now().minusMinutes(pendingTimeoutMinutes + nullKeyGraceMinutes);
        if (charge.getCreatedAt().isAfter(graceThreshold)) {
            return;
        }
        chargeFinalizer.finalizePending(charge, WalletCharge.Status.FAILED, null);
    }

    private void handleChargeWithKey(WalletCharge charge) {
        TossQueryResult result;
        try {
            result = tossPaymentClient.queryPaymentStatus(charge.getPgPaymentKey());
        } catch (Exception e) {
            LocalDateTime finalThreshold = LocalDateTime.now().minusMinutes(finalTimeoutMinutes);
            if (charge.getCreatedAt().isBefore(finalThreshold)) {
                log.error("[PaymentTtlScanner] WalletCharge {}분 경과, PG 조회 반복 실패로 강제 종결: chargeId={}",
                        finalTimeoutMinutes, charge.getId(), e);
                chargeFinalizer.finalizePending(charge, WalletCharge.Status.FAILED, null);
            } else {
                log.warn("[PaymentTtlScanner] WalletCharge PG 조회 실패, 다음 주기에 재시도: chargeId={}", charge.getId(), e);
            }
            return;
        }
        switch (result.status()) {
            case APPROVED -> chargeFinalizer.finalizePending(charge, WalletCharge.Status.APPROVED, result.pgTxId());
            case FAILED, NOT_FOUND -> chargeFinalizer.finalizePending(charge, WalletCharge.Status.FAILED, result.pgTxId());
        }
    }

    // 신-하자드9 — confirm이 PG호출까지는 갔는데 우리 쪽 조건부 UPDATE가 끊긴 좁은 케이스. 키로 PG에 직접 물어 회복.
    private void handleWithKey(Payment payment) {
        TossQueryResult result;
        try {
            result = tossPaymentClient.queryPaymentStatus(payment.getPgPaymentKey());
        } catch (Exception e) {
            // I2-3 — 조회 자체가 실패(예외/타임아웃)한 경우에만 8분 기준 강제컷오프 대상. 결정적 응답을 받은 적 없는 row.
            LocalDateTime finalThreshold = LocalDateTime.now().minusMinutes(finalTimeoutMinutes);
            if (payment.getCreatedAt().isBefore(finalThreshold)) {
                log.error("[PaymentTtlScanner] {}분 경과, PG 조회 반복 실패로 강제 종결: paymentId={}",
                        finalTimeoutMinutes, payment.getId(), e);
                finalizer.finalizePending(payment, Payment.Status.FAILED, null, "FORCED_TIMEOUT");
            } else {
                log.warn("[PaymentTtlScanner] PG 조회 실패, 다음 주기에 재시도: paymentId={}", payment.getId(), e);
            }
            return;
        }
        switch (result.status()) {
            case APPROVED -> finalizer.finalizePending(payment, Payment.Status.APPROVED, result.pgTxId(), null);
            case FAILED -> finalizer.finalizePending(payment, Payment.Status.FAILED, result.pgTxId(), "PG_REJECTED");
            case NOT_FOUND -> finalizer.finalizePending(payment, Payment.Status.FAILED, result.pgTxId(), "EXPIRED");
        }
    }
}
