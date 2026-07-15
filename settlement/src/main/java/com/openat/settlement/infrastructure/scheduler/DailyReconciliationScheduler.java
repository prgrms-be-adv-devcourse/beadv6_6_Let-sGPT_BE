package com.openat.settlement.infrastructure.scheduler;

import com.openat.settlement.application.dto.DailyReconciliationSummary;
import com.openat.settlement.application.service.DailyReconciliationService;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 정산 대사 새벽 2시 배치(reconciliation.md 실행 흐름, WS-3) — payment PG 대사(새벽 1시)보다 반드시 뒤에 실행되어야
// 한다(WS-0.2, 1시간 갭으로 여유 확보). "전일 날짜 계산 → 결제 모듈 API 호출 → 검증 → 동기화 → 저장 → 실패 시 관리자 확인".
@Slf4j
@Component
public class DailyReconciliationScheduler {

    private static final long LOCK_KEY = 917_150_200L;

    private final DailyReconciliationService dailyReconciliationService;
    private final PgAdvisoryLock advisoryLock;

    public DailyReconciliationScheduler(DailyReconciliationService dailyReconciliationService,
            PgAdvisoryLock advisoryLock) {
        this.dailyReconciliationService = dailyReconciliationService;
        this.advisoryLock = advisoryLock;
    }

    @Scheduled(cron = "${settlement.reconciliation.daily-cron:0 0 2 * * *}")
    public void run() {
        if (!advisoryLock.tryLock(LOCK_KEY)) {
            log.info("[DailyReconciliationScheduler] 다른 replica가 실행 중, 이번 주기는 건너뜀");
            return;
        }
        try {
            LocalDate businessDate = LocalDate.now().minusDays(1);
            DailyReconciliationSummary summary = dailyReconciliationService.reconcile(businessDate);
            log.info("[DailyReconciliationScheduler] 정산 대사 완료: {}", summary);
        } catch (Exception e) {
            log.error("[DailyReconciliationScheduler] 정산 대사 배치 실패", e);
        } finally {
            advisoryLock.unlock(LOCK_KEY);
        }
    }
}
