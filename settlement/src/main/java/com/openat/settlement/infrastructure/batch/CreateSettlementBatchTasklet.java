package com.openat.settlement.infrastructure.batch;

import com.openat.settlement.application.dto.CreateSettlementBatchCommand;
import com.openat.settlement.application.usecase.SettlementBatchUseCase;
import com.openat.settlement.domain.model.SettlementBatch;
import com.openat.settlement.domain.model.SettlementBatchType;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

/**
 * 월 정산 배치 시작 Tasklet입니다.
 */
@Component
@RequiredArgsConstructor
public class CreateSettlementBatchTasklet implements Tasklet {

    private final SettlementBatchUseCase settlementBatchUseCase;

    @Override
    public RepeatStatus execute(
            StepContribution contribution,
            ChunkContext chunkContext
    ) {
        String settlementMonth = contribution.getStepExecution()
                .getJobParameters()
                .getString("settlementMonth");

        SettlementBatch batch = settlementBatchUseCase.createAndStartBatch(
                new CreateSettlementBatchCommand(
                        settlementMonth,
                        SettlementBatchType.SETTLEMENT_RUN
                )
        );

        contribution.getStepExecution()
                .getJobExecution()
                .getExecutionContext()
                .putString("batchId", batch.getId().toString());

        return RepeatStatus.FINISHED;
    }
}
