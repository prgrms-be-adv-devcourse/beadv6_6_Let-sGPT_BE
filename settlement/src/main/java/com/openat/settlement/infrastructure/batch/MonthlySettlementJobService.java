package com.openat.settlement.infrastructure.batch;

import com.openat.common.exception.BusinessException;
import com.openat.settlement.application.dto.RunMonthlySettlementResult;
import com.openat.settlement.application.usecase.MonthlySettlementJobUseCase;
import com.openat.settlement.domain.exception.SettlementErrorCode;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class MonthlySettlementJobService implements MonthlySettlementJobUseCase {

  private static final DateTimeFormatter SETTLEMENT_MONTH_FORMATTER =
      new DateTimeFormatterBuilder()
          .appendPattern("uuuuMM")
          .toFormatter()
          .withResolverStyle(ResolverStyle.STRICT);

  private final JobLauncher jobLauncher;
  private final Job monthlySettlementJob;

  public MonthlySettlementJobService(
      JobLauncher jobLauncher, @Qualifier("monthlySettlementJob") Job monthlySettlementJob) {
    this.jobLauncher = jobLauncher;
    this.monthlySettlementJob = monthlySettlementJob;
  }

  @Override
  public RunMonthlySettlementResult run(String settlementMonth) {
    validateSettlementMonth(settlementMonth);

    JobParameters jobParameters =
        new JobParametersBuilder()
            .addString("jobType", "MONTHLY_SETTLEMENT")
            .addString("settlementMonth", settlementMonth)
            .addString("triggerType", "MANUAL")
            .addLong("requestedAt", System.currentTimeMillis())
            .toJobParameters();

    try {
      JobExecution execution = jobLauncher.run(monthlySettlementJob, jobParameters);
      log.info(
          "Manual monthly settlement job launched. settlementMonth={}, jobExecutionId={}, status={}",
          settlementMonth,
          execution.getId(),
          execution.getStatus());
      return new RunMonthlySettlementResult(
          execution.getId(), settlementMonth, execution.getStatus().name());
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error(
          "Failed to launch manual monthly settlement job. settlementMonth={}", settlementMonth, e);
      throw new BusinessException(
          SettlementErrorCode.BATCH_MONTHLY_LAUNCH_FAILED,
          "월 정산 수동 실행에 실패했습니다. settlementMonth=" + settlementMonth,
          e);
    }
  }

  private void validateSettlementMonth(String settlementMonth) {
    try {
      YearMonth.parse(settlementMonth, SETTLEMENT_MONTH_FORMATTER);
    } catch (DateTimeParseException | NullPointerException e) {
      throw new BusinessException(
          SettlementErrorCode.INVALID_SETTLEMENT_MONTH,
          "정산월은 yyyyMM 형식이어야 합니다. settlementMonth=" + settlementMonth,
          e);
    }
  }
}
