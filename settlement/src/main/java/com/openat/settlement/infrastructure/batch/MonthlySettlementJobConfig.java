package com.openat.settlement.infrastructure.batch;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 매월 1일 새벽 3시에 실행되는 월 정산 Job 설정입니다.
 *
 * 별도 설정 클래스 파일을 사용하지 않고
 * @Value("${settlement.batch.grid-size}") 방식으로 gridSize를 직접 주입받습니다.
 */
@Configuration
@RequiredArgsConstructor
public class MonthlySettlementJobConfig {

    public static final String MONTHLY_SETTLEMENT_JOB = "monthlySettlementJob";

    private final CreateSettlementBatchTasklet createSettlementBatchTasklet;
    private final FinalizeSettlementBatchTasklet finalizeSettlementBatchTasklet;
    private final SellerSettlementWorkerTasklet sellerSettlementWorkerTasklet;

    /**
     * partition 병렬 실행 개수입니다.
     *
     * application.yml:
     * settlement.batch.grid-size
     *
     * 기본값을 코드에 두지 않고 application.yml 값을 반드시 사용합니다.
     */
    @Value("${settlement.batch.grid-size}")
    private int gridSize;

    @Bean
    public Job monthlySettlementJob(
            JobRepository jobRepository,
            Step createSettlementBatchStep,
            Step sellerSettlementMasterStep,
            Step finalizeSettlementBatchStep,
            SettlementBatchFailureListener settlementBatchFailureListener
    ) {
        return new JobBuilder(MONTHLY_SETTLEMENT_JOB, jobRepository)
                .start(createSettlementBatchStep)
                .next(sellerSettlementMasterStep)
                .next(finalizeSettlementBatchStep)
                .listener(settlementBatchFailureListener)
                .build();
    }

    @Bean
    public Step createSettlementBatchStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager
    ) {
        return new StepBuilder("createSettlementBatchStep", jobRepository)
                .tasklet(createSettlementBatchTasklet, transactionManager)
                .build();
    }

    /**
     * Master Step:
     * sellerId 목록을 partition으로 나누고 Worker Step을 병렬 실행합니다.
     */
    @Bean
    public Step sellerSettlementMasterStep(
            JobRepository jobRepository,
            SellerSettlementPartitioner sellerSettlementPartitioner,
            TaskExecutorPartitionHandler sellerSettlementPartitionHandler
    ) {
        return new StepBuilder("sellerSettlementMasterStep", jobRepository)
                .partitioner("sellerSettlementWorkerStep", sellerSettlementPartitioner)
                .partitionHandler(sellerSettlementPartitionHandler)
                .build();
    }

    /**
     * Worker Step:
     * partition 하나에 들어온 sellerId 목록을 실제 처리합니다.
     */
    @Bean
    public Step sellerSettlementWorkerStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager
    ) {
        return new StepBuilder("sellerSettlementWorkerStep", jobRepository)
                .tasklet(sellerSettlementWorkerTasklet, transactionManager)
                .build();
    }

    @Bean
    public TaskExecutorPartitionHandler sellerSettlementPartitionHandler(
            Step sellerSettlementWorkerStep,
            @Qualifier("settlementTaskExecutor") TaskExecutor settlementTaskExecutor
    ) {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setStep(sellerSettlementWorkerStep);
        handler.setTaskExecutor(settlementTaskExecutor);
        handler.setGridSize(gridSize);
        return handler;
    }

    @Bean
    public Step finalizeSettlementBatchStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager
    ) {
        return new StepBuilder("finalizeSettlementBatchStep", jobRepository)
                .tasklet(finalizeSettlementBatchTasklet, transactionManager)
                .build();
    }
}
