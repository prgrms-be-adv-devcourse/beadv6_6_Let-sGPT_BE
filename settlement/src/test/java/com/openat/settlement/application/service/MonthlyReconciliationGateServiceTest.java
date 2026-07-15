package com.openat.settlement.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.settlement.domain.exception.SettlementErrorCode;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationResultJpaEntity;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationResultJpaEntity.Status;
import com.openat.settlement.infrastructure.reconciliation.DailyReconciliationResultJpaRepository;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MonthlyReconciliationGateServiceTest {

  @Mock private DailyReconciliationResultJpaRepository resultRepository;

  private MonthlyReconciliationGateService service;

  @BeforeEach
  void setUp() {
    service = new MonthlyReconciliationGateService(resultRepository);
  }

  @Test
  void passesWhenEveryDayIsSuccessfullyReconciled() {
    YearMonth month = YearMonth.of(2026, 2);
    when(resultRepository.findAllByBusinessDateBetweenOrderByBusinessDateAsc(
            month.atDay(1), month.atEndOfMonth()))
        .thenReturn(successResults(month));

    service.verify("202602");
  }

  @Test
  void blocksWhenAReconciliationDateIsMissing() {
    YearMonth month = YearMonth.of(2026, 2);
    LocalDate missingDate = LocalDate.of(2026, 2, 14);
    List<DailyReconciliationResultJpaEntity> results =
        successResults(month).stream()
            .filter(result -> !result.getBusinessDate().equals(missingDate))
            .toList();
    when(resultRepository.findAllByBusinessDateBetweenOrderByBusinessDateAsc(
            month.atDay(1), month.atEndOfMonth()))
        .thenReturn(results);

    BusinessException exception =
        catchThrowableOfType(BusinessException.class, () -> service.verify("202602"));

    assertThat(exception.getErrorCode())
        .isEqualTo(SettlementErrorCode.BATCH_RECONCILIATION_BLOCKED);
    assertThat(exception).hasMessageContaining("missingCount=1").hasMessageContaining("2026-02-14");
  }

  @Test
  void blocksWhenAnyReconciliationFailed() {
    YearMonth month = YearMonth.of(2026, 2);
    LocalDate failedDate = LocalDate.of(2026, 2, 20);
    List<DailyReconciliationResultJpaEntity> results =
        successResults(month).stream()
            .map(
                result ->
                    result.getBusinessDate().equals(failedDate)
                        ? result(failedDate, Status.DISCREPANCY_FOUND, 2)
                        : result)
            .toList();
    when(resultRepository.findAllByBusinessDateBetweenOrderByBusinessDateAsc(
            month.atDay(1), month.atEndOfMonth()))
        .thenReturn(results);

    BusinessException exception =
        catchThrowableOfType(BusinessException.class, () -> service.verify("202602"));

    assertThat(exception.getErrorCode())
        .isEqualTo(SettlementErrorCode.BATCH_RECONCILIATION_BLOCKED);
    assertThat(exception)
        .hasMessageContaining("failedCount=1")
        .hasMessageContaining("2026-02-20:DISCREPANCY_FOUND:discrepancies=2");
  }

  @Test
  void blocksWhenReconciliationApiCallFailed() {
    YearMonth month = YearMonth.of(2026, 2);
    LocalDate failedDate = LocalDate.of(2026, 2, 21);
    List<DailyReconciliationResultJpaEntity> results =
        successResults(month).stream()
            .map(
                result ->
                    result.getBusinessDate().equals(failedDate)
                        ? result(failedDate, Status.CALL_FAILED, 0)
                        : result)
            .toList();
    when(resultRepository.findAllByBusinessDateBetweenOrderByBusinessDateAsc(
            month.atDay(1), month.atEndOfMonth()))
        .thenReturn(results);

    BusinessException exception =
        catchThrowableOfType(BusinessException.class, () -> service.verify("202602"));

    assertThat(exception.getErrorCode())
        .isEqualTo(SettlementErrorCode.BATCH_RECONCILIATION_BLOCKED);
    assertThat(exception)
        .hasMessageContaining("failedCount=1")
        .hasMessageContaining("2026-02-21:CALL_FAILED:discrepancies=0");
  }

  @Test
  void blocksInvalidSettlementMonth() {
    BusinessException exception =
        catchThrowableOfType(BusinessException.class, () -> service.verify("2026-02"));

    assertThat(exception.getErrorCode())
        .isEqualTo(SettlementErrorCode.BATCH_RECONCILIATION_BLOCKED);
    assertThat(exception).hasMessageContaining("Invalid settlementMonth");
  }

  private List<DailyReconciliationResultJpaEntity> successResults(YearMonth month) {
    return month
        .atDay(1)
        .datesUntil(month.atEndOfMonth().plusDays(1))
        .map(date -> result(date, Status.SUCCESS, 0))
        .toList();
  }

  private DailyReconciliationResultJpaEntity result(
      LocalDate date, Status status, int discrepancyCount) {
    return new DailyReconciliationResultJpaEntity(date, status, 0, 0L, 0, 0L, 0L, discrepancyCount);
  }
}
