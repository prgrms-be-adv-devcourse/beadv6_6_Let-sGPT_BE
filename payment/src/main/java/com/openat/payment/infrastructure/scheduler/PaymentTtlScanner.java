package com.openat.payment.infrastructure.scheduler;

import com.openat.payment.application.client.TossPaymentClient;
import com.openat.payment.application.client.TossQueryResult;
import com.openat.payment.domain.model.Payment;
import com.openat.payment.domain.repository.PaymentRepository;
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
    private final TossPaymentClient tossPaymentClient;
    private final PaymentTtlFinalizer finalizer;

    // N분 — 이 시간이 지난 PAYMENT_PENDING이 스캔 대상. 운영 기본값 10분, 시연/테스트 시 application-local.yml에서 짧게 오버라이드.
    @Value("${payment.ttl-scanner.pending-timeout-minutes:10}")
    private long pendingTimeoutMinutes;

    // M분(추가 그레이스) — pgPaymentKey가 NULL인 row만 적용.
    // confirm 자체를 호출한 적이 없으면 PG에 물어볼 키가 없어 시간 기반으로만 확정할 수 있어 더 신중하게 기다린다.
    @Value("${payment.ttl-scanner.null-key-grace-minutes:5}")
    private long nullKeyGraceMinutes;

    public PaymentTtlScanner(PaymentRepository paymentRepository, TossPaymentClient tossPaymentClient,
            PaymentTtlFinalizer finalizer) {
        this.paymentRepository = paymentRepository;
        this.tossPaymentClient = tossPaymentClient;
        this.finalizer = finalizer;
    }

    @Scheduled(fixedDelay = 60_000)
    public void scan() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(pendingTimeoutMinutes);
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

    // 신-하자드9 — confirm이 PG호출까지는 갔는데 우리 쪽 조건부 UPDATE가 끊긴 좁은 케이스. 키로 PG에 직접 물어 회복.
    private void handleWithKey(Payment payment) {
        TossQueryResult result = tossPaymentClient.queryPaymentStatus(payment.getPgPaymentKey());
        switch (result.status()) {
            case APPROVED -> finalizer.finalizePending(payment, Payment.Status.APPROVED, result.pgTxId(), null);
            case FAILED -> finalizer.finalizePending(payment, Payment.Status.FAILED, result.pgTxId(), "PG_REJECTED");
            case NOT_FOUND -> finalizer.finalizePending(payment, Payment.Status.FAILED, result.pgTxId(), "EXPIRED");
        }
    }
}
