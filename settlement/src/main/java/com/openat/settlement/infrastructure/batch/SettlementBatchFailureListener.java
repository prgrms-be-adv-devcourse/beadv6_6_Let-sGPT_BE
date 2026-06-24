package com.openat.settlement.infrastructure.batch;

import com.openat.settlement.application.dto.FailSettlementBatchCommand;
import com.openat.settlement.application.usecase.SettlementBatchUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementBatchFailureListener implements JobExecutionListener {  // Spring Batch Job의 실행 전/후에 끼어들 수 있는 리스너

    private static final int MAX_FAIL_REASON_LENGTH = 500; // 실패 사유 500글자 제한

    private final SettlementBatchUseCase settlementBatchUseCase;

    @Override
    public void afterJob(JobExecution jobExecution) {  // 배치 Job이 끝난 뒤에 실행되는 코드
        if (jobExecution.getStatus() != BatchStatus.FAILED) { // 성공했거나 진행 중이거나 실패가 아니면 아무것도 안 함
            return;
        }

        // 정산 배치 테이블 ID
        String batchIdText = jobExecution.getExecutionContext().getString("batchId", null);
        if (batchIdText == null || batchIdText.isBlank()) {
            log.warn(
                    "월간 정산 작업이 정산 배치(batch) 생성 전에 실패했습니다. jobExecutionId={}",
                    jobExecution.getId()
            );
            return;
        }

        String reason = buildFailReason(jobExecution); // Job 실행 중 발생한 예외들을 모아서 문자열로 만듬
        settlementBatchUseCase.failBatch(
                // 해당 정산 배치 ID를 찾아서
                // 상태를 FAILED로 바꾸고
                // 실패 사유를 저장
                new FailSettlementBatchCommand(UUID.fromString(batchIdText), reason)
        );
    }

    private String buildFailReason(JobExecution jobExecution) {
        String reason = jobExecution.getAllFailureExceptions()
                .stream()
                .map(exception -> exception.getClass().getSimpleName() + ": " + exception.getMessage())
                .collect(Collectors.joining(" | "));

        if (reason.isBlank()) {
            reason = "Spring Batch job failed. jobExecutionId=" + jobExecution.getId();
        }

        return reason.length() <= MAX_FAIL_REASON_LENGTH
                ? reason
                : reason.substring(0, MAX_FAIL_REASON_LENGTH);
    }
}
