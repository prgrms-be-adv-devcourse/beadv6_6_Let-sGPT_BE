package com.openat.settlement.presentation.controller;

import com.openat.settlement.application.dto.DailyReconciliationSummary;
import com.openat.settlement.application.service.DailyReconciliationService;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 수동 트리거(WS-3 DoD "대사 배치 수동 트리거로 불일치 검출 시연") — 새벽 배치를 기다리지 않고 즉시 실행.
@RestController
public class AdminReconciliationController {

    private final DailyReconciliationService dailyReconciliationService;

    public AdminReconciliationController(DailyReconciliationService dailyReconciliationService) {
        this.dailyReconciliationService = dailyReconciliationService;
    }

    @PostMapping("/api/v1/admin/reconciliation/run")
    public DailyReconciliationSummary run(@RequestParam(required = false) LocalDate businessDate) {
        LocalDate target = businessDate != null ? businessDate : LocalDate.now().minusDays(1);
        return dailyReconciliationService.reconcile(target);
    }
}
