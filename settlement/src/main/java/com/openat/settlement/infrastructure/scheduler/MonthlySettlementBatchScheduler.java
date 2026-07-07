package com.openat.settlement.infrastructure.scheduler;

import com.openat.common.exception.BusinessException;
import com.openat.settlement.domain.exception.SettlementErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 매월 1일 새벽 3시에 월 정산 Job을 실행합니다.
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MonthlySettlementBatchScheduler {

    private final JobLauncher jobLauncher;

    @Qualifier("monthlySettlementJob")
    private final Job monthlySettlementJob;

    @Scheduled(cron = "${settlement.batch.monthly-cron}")
    public void runMonthlySettlementJob() {
        String settlementMonth = calculatePreviousMonth();

        // 배치 실행에 필요한 파라미터를 만드는 부분
        JobParameters jobParameters = new JobParametersBuilder()
                .addString("jobType", "MONTHLY_SETTLEMENT")
                .addString("settlementMonth", settlementMonth)
                // 같은 정산월을 재실행할 수 있게 requestedAt을 유니크 파라미터로 추가
                .addLong("requestedAt", System.currentTimeMillis())
                .toJobParameters();

        try {
            // 실제 월 정산 배치를 실행
            // 정산 배치 생성
            //   → 판매자별 정산 처리
            //   → 배치 결과 확정
            JobExecution execution = jobLauncher.run(monthlySettlementJob, jobParameters);
            log.info(
                    "Monthly settlement job launched. settlementMonth={}, jobExecutionId={}, status={}",
                    settlementMonth,
                    execution.getId(),
                    execution.getStatus()
            );
        } catch (Exception e) {
            log.error("Failed to launch monthly settlement job. settlementMonth={}", settlementMonth, e);
            throw new BusinessException(
                    SettlementErrorCode.BATCH_MONTHLY_LAUNCH_FAILED,
                    "월 정산 배치 실행에 실패했습니다. settlementMonth=" + settlementMonth,
                    e
            );
        }
    }

    /**
     * 전월을 yyyyMM 형식으로 계산합니다.
     */
    private String calculatePreviousMonth() {
        return LocalDate.now()
                .minusMonths(1)
                .format(DateTimeFormatter.ofPattern("yyyyMM"));
    }
}
