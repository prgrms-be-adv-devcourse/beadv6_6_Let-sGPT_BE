package com.openat.settlement.infrastructure.batch;

import com.openat.settlement.application.dto.FinalizeSettlementBatchCommand;
import com.openat.settlement.application.usecase.SettlementBatchUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 월 정산 배치 종료 Tasklet입니다.
 */
@Component
@RequiredArgsConstructor
public class FinalizeSettlementBatchTasklet implements Tasklet {

    private final SettlementBatchUseCase settlementBatchUseCase;

    @Override
    public RepeatStatus execute(
            StepContribution contribution,
            ChunkContext chunkContext
    ) {
        String batchId = contribution.getStepExecution()
                .getJobExecution()
                .getExecutionContext()
                .getString("batchId");

        settlementBatchUseCase.finalizeSettlementRunBatch(
                new FinalizeSettlementBatchCommand(UUID.fromString(batchId))
        );

        return RepeatStatus.FINISHED;
    }
}
