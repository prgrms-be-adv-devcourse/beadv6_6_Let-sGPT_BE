package com.openat.settlement.presentation.controller;

import com.openat.settlement.application.dto.DailyReconciliationSummary;
import com.openat.settlement.application.service.DailyReconciliationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 새벽 일별 대사 배치를 기다리지 않고 지정한 영업일의 대사를 즉시 실행하는 관리자 API입니다. */
@Tag(name = "정산 대사 관리", description = "결제 모듈과 정산 모듈의 일별 결제·환불 데이터를 비교하고 누락 데이터를 복구합니다.")
@RestController
public class AdminReconciliationController {

  private final DailyReconciliationService dailyReconciliationService;

  public AdminReconciliationController(DailyReconciliationService dailyReconciliationService) {
    this.dailyReconciliationService = dailyReconciliationService;
  }

  @Operation(
      summary = "일별 정산 대사 수동 실행",
      description =
          "지정한 영업일의 결제·환불 데이터를 payment 모듈에서 조회하여 settlement 데이터와 비교합니다. "
              + "settlement_orders 또는 settlement_refunds에 누락된 데이터는 가능한 경우 복구 저장하며, "
              + "실행 결과로 SUCCESS, DISCREPANCY_FOUND 또는 CALL_FAILED 상태와 불일치 건수를 반환합니다. "
              + "businessDate를 생략하면 전일을 대상으로 실행합니다.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "일별 대사 실행 완료"),
    @ApiResponse(responseCode = "400", description = "businessDate 날짜 형식 오류"),
    @ApiResponse(responseCode = "401", description = "인증되지 않은 요청"),
    @ApiResponse(responseCode = "403", description = "관리자 권한 없음")
  })
  @PostMapping("/api/v1/settlements/admin/reconciliation/run")
  public DailyReconciliationSummary run(
      @Parameter(
              name = "businessDate",
              description = "대사 대상 영업일(YYYY-MM-DD). 생략하면 전일을 사용합니다.",
              example = "2026-07-14",
              required = false,
              schema =
                  @Schema(
                      type = "string",
                      format = "date",
                      pattern = "^\\d{4}-\\d{2}-\\d{2}$"))
          @RequestParam(name = "businessDate", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate businessDate) {
    LocalDate target = businessDate != null ? businessDate : LocalDate.now().minusDays(1);
    return dailyReconciliationService.reconcile(target);
  }
}
