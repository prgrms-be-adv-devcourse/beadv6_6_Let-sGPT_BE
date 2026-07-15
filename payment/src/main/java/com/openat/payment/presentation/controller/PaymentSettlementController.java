package com.openat.payment.presentation.controller;

import com.openat.payment.application.service.DailyPaymentSettlementService;
import com.openat.payment.presentation.dto.DailyPaymentSettlementResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 정산 대사 일별 API(personal_workplan/plan/reconciliation.md) — settlement가 새벽 2시 배치에서 호출한다(WS-3).
@Tag(name = "PaymentSettlement", description = "정산팀 대사 배치용 일별 결제·환불 조회")
@RestController
public class PaymentSettlementController {

    private final DailyPaymentSettlementService dailyPaymentSettlementService;

    public PaymentSettlementController(DailyPaymentSettlementService dailyPaymentSettlementService) {
        this.dailyPaymentSettlementService = dailyPaymentSettlementService;
    }

    @Operation(summary = "일별 결제·환불 조회", description = "PG 대사(WS-0) MATCHED 행만 반환한다.")
    @GetMapping("/internal/v1/payment-settlement/daily")
    public ResponseEntity<DailyPaymentSettlementResponse> daily(
            @Parameter(description = "영업일", required = true) @RequestParam LocalDate businessDate) {
        return ResponseEntity.ok(dailyPaymentSettlementService.getDaily(businessDate));
    }
}
