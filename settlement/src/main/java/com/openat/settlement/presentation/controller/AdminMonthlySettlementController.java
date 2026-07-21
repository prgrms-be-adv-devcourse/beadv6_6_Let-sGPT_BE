package com.openat.settlement.presentation.controller;

import com.openat.settlement.application.dto.RunMonthlySettlementResult;
import com.openat.settlement.application.usecase.MonthlySettlementJobUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${api.init}/settlements/admin")
public class AdminMonthlySettlementController {

  private final MonthlySettlementJobUseCase monthlySettlementJobUseCase;

  public AdminMonthlySettlementController(MonthlySettlementJobUseCase monthlySettlementJobUseCase) {
    this.monthlySettlementJobUseCase = monthlySettlementJobUseCase;
  }

  @Operation(
      summary = "월 정산 수동 실행",
      description = "지정한 정산월의 월 정산 배치를 수동으로 실행합니다. 일별 대사 검증은 자동 실행과 동일하게 적용됩니다.")
  @PostMapping("monthly/run")
  public ResponseEntity<RunMonthlySettlementResponse> runMonthlySettlement(
      @Parameter(description = "정산월(yyyyMM)", example = "202607", required = true) @RequestParam
          String settlementMonth) {
    RunMonthlySettlementResult result = monthlySettlementJobUseCase.run(settlementMonth);
    return ResponseEntity.ok(RunMonthlySettlementResponse.from(result));
  }

  public record RunMonthlySettlementResponse(
      Long jobExecutionId, String settlementMonth, String status) {

    private static RunMonthlySettlementResponse from(RunMonthlySettlementResult result) {
      return new RunMonthlySettlementResponse(
          result.jobExecutionId(), result.settlementMonth(), result.status());
    }
  }
}
