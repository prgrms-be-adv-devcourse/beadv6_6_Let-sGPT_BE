package com.openat.settlement.infrastructure.batch;

import com.openat.settlement.application.service.MonthlyReconciliationGateService;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReconciliationGateTasklet implements Tasklet {

  private final MonthlyReconciliationGateService reconciliationGateService;

  @Override
  public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
    String settlementMonth =
        contribution.getStepExecution().getJobParameters().getString("settlementMonth");

    reconciliationGateService.verify(settlementMonth);
    return RepeatStatus.FINISHED;
  }
}
