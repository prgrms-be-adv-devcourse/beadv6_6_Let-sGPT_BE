package com.openat.payment.infrastructure.scheduler;

import com.openat.payment.application.dto.PgReconciliationSummary;
import com.openat.payment.application.service.PgReconciliationService;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// WS-0.2 — 정산 대사(settlement, 새벽 2시)보다 먼저 실행되어야 한다. 정산 대사가 pull하는 payment 일별 API가
// pg_recon_status=MATCHED 행만 노출하므로, 이 배치가 먼저 돌아 전일치를 검증해둬야 한다.
@Slf4j
@Component
public class PgReconciliationScheduler {

    // advisory lock 키 — 임의 상수(payment 모듈 내 다른 스케줄러와 겹치지 않게 고정값 사용).
    private static final long LOCK_KEY = 917_150_100L;

    private final PgReconciliationService pgReconciliationService;
    private final PgAdvisoryLock advisoryLock;

    public PgReconciliationScheduler(PgReconciliationService pgReconciliationService, PgAdvisoryLock advisoryLock) {
        this.pgReconciliationService = pgReconciliationService;
        this.advisoryLock = advisoryLock;
    }

    @Scheduled(cron = "${payment.reconciliation.pg.cron:0 0 1 * * *}")
    public void run() {
        if (!advisoryLock.tryLock(LOCK_KEY)) {
            log.info("[PgReconciliationScheduler] 다른 replica가 실행 중, 이번 주기는 건너뜀");
            return;
        }
        try {
            LocalDate businessDate = LocalDate.now().minusDays(1);
            PgReconciliationSummary summary = pgReconciliationService.reconcile(businessDate);
            log.info("[PgReconciliationScheduler] PG 대사 완료: {}", summary);
        } catch (Exception e) {
            log.error("[PgReconciliationScheduler] PG 대사 배치 실패", e);
        } finally {
            advisoryLock.unlock(LOCK_KEY);
        }
    }
}
