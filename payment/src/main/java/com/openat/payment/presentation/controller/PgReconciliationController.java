package com.openat.payment.presentation.controller;

import com.openat.payment.application.dto.PgReconciliationSummary;
import com.openat.payment.application.service.PgReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 수동 트리거(WS-0 DoD "대사 배치 수동 트리거로 불일치 검출 시연") — 새벽 배치를 기다리지 않고 즉시 실행.
@Tag(name = "PgReconciliation", description = "PG 대사(payment DB ↔ 토스) 수동 트리거")
@RestController
public class PgReconciliationController {

    private final PgReconciliationService pgReconciliationService;

    public PgReconciliationController(PgReconciliationService pgReconciliationService) {
        this.pgReconciliationService = pgReconciliationService;
    }

    @Operation(summary = "PG 대사 수동 실행", description = "지정한 영업일(businessDate) 기준으로 PG 대사를 즉시 실행한다.")
    @PostMapping("/internal/v1/pg-reconciliation/run")
    public ResponseEntity<PgReconciliationSummary> run(
            @RequestParam(required = false) LocalDate businessDate) {
        LocalDate target = businessDate != null ? businessDate : LocalDate.now().minusDays(1);
        return ResponseEntity.ok(pgReconciliationService.reconcile(target));
    }
}
