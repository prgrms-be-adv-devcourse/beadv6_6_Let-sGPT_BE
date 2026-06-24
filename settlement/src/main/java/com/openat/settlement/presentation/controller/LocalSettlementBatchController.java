package com.openat.settlement.presentation.controller;

import com.openat.settlement.domain.exception.SettlementErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Profile("local")
@RestController
@RequiredArgsConstructor
@RequestMapping("/local-samples/settlements")
public class LocalSettlementBatchController {

    private final Map<UUID, CompletableFuture<LocalSettlementBatchResponse>> batchRuns = new ConcurrentHashMap<>();

    private final JobLauncher jobLauncher;

    @Qualifier("localBatchLaunchExecutor")
    private final TaskExecutor localBatchLaunchExecutor;

    @Qualifier("monthlySettlementJob")
    private final Job monthlySettlementJob;

    @PostMapping("/monthly-batch")
    public LocalSettlementBatchResponse runMonthlyBatch(
            @RequestParam String settlementMonth,
            @RequestParam(required = false) Long requestedAt
    ) {
        UUID runId = UUID.randomUUID();
        long requestTime = requestedAt == null ? System.currentTimeMillis() : requestedAt;
        LocalDateTime startedAt = LocalDateTime.now();

        CompletableFuture<LocalSettlementBatchResponse> future = CompletableFuture.supplyAsync(
                () -> executeBatch(runId, settlementMonth, requestTime, startedAt),
                localBatchLaunchExecutor::execute
        );
        batchRuns.put(runId, future);

        return LocalSettlementBatchResponse.running(runId, startedAt, settlementMonth);
    }

    @GetMapping("/monthly-batch/{runId}")
    public LocalSettlementBatchResponse getMonthlyBatchStatus(@PathVariable UUID runId) {
        CompletableFuture<LocalSettlementBatchResponse> future = batchRuns.get(runId);

        if (future == null) {
            return LocalSettlementBatchResponse.notFound(runId);
        }

        if (!future.isDone()) {
            return LocalSettlementBatchResponse.running(runId, null, null);
        }

        return future.join();
    }

    private LocalSettlementBatchResponse executeBatch(
            UUID runId,
            String settlementMonth,
            long requestTime,
            LocalDateTime startedAt
    ) {
        try {
            JobParameters jobParameters = new JobParametersBuilder()
                    .addString("jobType", "LOCAL_TEST")
                    .addString("settlementMonth", settlementMonth)
                    .addLong("requestedAt", requestTime)
                    .toJobParameters();

            JobExecution execution = jobLauncher.run(monthlySettlementJob, jobParameters);

            return new LocalSettlementBatchResponse(
                    runId,
                    execution.getJobInstanceId(),
                    execution.getId(),
                    execution.getStatus().name(),
                    execution.getExitStatus().getExitCode(),
                    null,
                    null,
                    startedAt,
                    LocalDateTime.now(),
                    settlementMonth
            );
        } catch (Exception e) {
            return new LocalSettlementBatchResponse(
                    runId,
                    null,
                    null,
                    "FAILED",
                    null,
                    SettlementErrorCode.BATCH_LOCAL_MONTHLY_FAILED.getCode(),
                    e.getClass().getSimpleName() + ": " + e.getMessage(),
                    startedAt,
                    LocalDateTime.now(),
                    settlementMonth
            );
        }
    }

    public record LocalSettlementBatchResponse(
            UUID runId,
            Long jobInstanceId,
            Long jobExecutionId,
            String status,
            String exitCode,
            String errorCode,
            String error,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            String settlementMonth
    ) {
        private static LocalSettlementBatchResponse running(
                UUID runId,
                LocalDateTime startedAt,
                String settlementMonth
        ) {
            return new LocalSettlementBatchResponse(
                    runId,
                    null,
                    null,
                    BatchStatus.STARTING.name(),
                    null,
                    null,
                    null,
                    startedAt,
                    null,
                    settlementMonth
            );
        }

        private static LocalSettlementBatchResponse notFound(UUID runId) {
            return new LocalSettlementBatchResponse(
                    runId,
                    null,
                    null,
                    "NOT_FOUND",
                    null,
                    SettlementErrorCode.BATCH_SETTLEMENT_NOT_FOUND.getCode(),
                    "No local batch run found. runId=" + runId,
                    null,
                    LocalDateTime.now(),
                    null
            );
        }
    }
}
