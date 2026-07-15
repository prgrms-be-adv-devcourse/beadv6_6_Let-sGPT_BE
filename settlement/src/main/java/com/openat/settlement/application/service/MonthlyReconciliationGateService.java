package com.openat.settlement.application.service;

import com.openat.common.exception.BusinessException;
import com.openat.settlement.domain.exception.SettlementErrorCode;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationResultJpaEntity;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationResultJpaEntity.Status;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationResultJpaRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MonthlyReconciliationGateService {

  private static final DateTimeFormatter SETTLEMENT_MONTH_FORMATTER =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuuMM")
          .toFormatter()
          .withResolverStyle(ResolverStyle.STRICT);
  private static final int FAILURE_DETAIL_LIMIT = 5;

  private final DailyReconciliationResultJpaRepository resultRepository;

  public MonthlyReconciliationGateService(DailyReconciliationResultJpaRepository resultRepository) {
    this.resultRepository = resultRepository;
  }

  @Transactional(readOnly = true)
  public void verify(String settlementMonth) {
    YearMonth targetMonth = parseSettlementMonth(settlementMonth);
    LocalDate from = targetMonth.atDay(1);
    LocalDate to = targetMonth.atEndOfMonth();

    List<DailyReconciliationResultJpaEntity> results =
        resultRepository.findAllByBusinessDateBetweenOrderByBusinessDateAsc(from, to);
    Map<LocalDate, DailyReconciliationResultJpaEntity> resultsByDate =
        results.stream()
            .collect(
                Collectors.toMap(
                    DailyReconciliationResultJpaEntity::getBusinessDate, Function.identity()));

    List<LocalDate> missingDates =
        from.datesUntil(to.plusDays(1)).filter(date -> !resultsByDate.containsKey(date)).toList();
    List<DailyReconciliationResultJpaEntity> failedResults =
        results.stream()
            .filter(
                result -> result.getStatus() != Status.SUCCESS || result.getDiscrepancyCount() > 0)
            .toList();

    if (!missingDates.isEmpty() || !failedResults.isEmpty()) {
      throw new BusinessException(
          SettlementErrorCode.BATCH_RECONCILIATION_BLOCKED,
          buildFailureMessage(settlementMonth, missingDates, failedResults));
    }
  }

  private YearMonth parseSettlementMonth(String settlementMonth) {
    try {
      return YearMonth.parse(settlementMonth, SETTLEMENT_MONTH_FORMATTER);
    } catch (DateTimeParseException | NullPointerException e) {
      throw new BusinessException(
          SettlementErrorCode.BATCH_RECONCILIATION_BLOCKED,
          "Invalid settlementMonth for reconciliation gate: " + settlementMonth,
          e);
    }
  }

  private String buildFailureMessage(
      String settlementMonth,
      List<LocalDate> missingDates,
      List<DailyReconciliationResultJpaEntity> failedResults) {
    String missing =
        missingDates.stream()
            .limit(FAILURE_DETAIL_LIMIT)
            .map(LocalDate::toString)
            .collect(Collectors.joining(","));
    String failed =
        failedResults.stream()
            .limit(FAILURE_DETAIL_LIMIT)
            .map(
                result ->
                    result.getBusinessDate()
                        + ":"
                        + result.getStatus()
                        + ":discrepancies="
                        + result.getDiscrepancyCount())
            .collect(Collectors.joining(","));

    return "Reconciliation gate blocked settlementMonth="
        + settlementMonth
        + ", missingCount="
        + missingDates.size()
        + ", missingDates=["
        + missing
        + "]"
        + ", failedCount="
        + failedResults.size()
        + ", failedDates=["
        + failed
        + "]";
  }
}
