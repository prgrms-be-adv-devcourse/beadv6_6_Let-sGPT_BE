package com.openat.settlement.infrastructure.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openat.common.exception.BusinessException;
import com.openat.settlement.application.dto.FailSettlementBatchCommand;
import com.openat.settlement.application.usecase.SettlementBatchUseCase;
import com.openat.settlement.domain.exception.SettlementErrorCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;

class SettlementBatchFailureListenerTest {

  @Test
  void marksCreatedBatchAsFailedWhenReconciliationGateBlocksJob() {
    SettlementBatchUseCase settlementBatchUseCase = mock(SettlementBatchUseCase.class);
    SettlementBatchFailureListener listener =
        new SettlementBatchFailureListener(settlementBatchUseCase);
    JobExecution jobExecution = mock(JobExecution.class);
    UUID batchId = UUID.randomUUID();
    ExecutionContext executionContext = new ExecutionContext();
    executionContext.putString("batchId", batchId.toString());

    when(jobExecution.getStatus()).thenReturn(BatchStatus.FAILED);
    when(jobExecution.getExecutionContext()).thenReturn(executionContext);
    when(jobExecution.getAllFailureExceptions())
        .thenReturn(
            List.of(
                new BusinessException(
                    SettlementErrorCode.BATCH_RECONCILIATION_BLOCKED,
                    "Reconciliation gate blocked settlementMonth=202602")));

    listener.afterJob(jobExecution);

    ArgumentCaptor<FailSettlementBatchCommand> commandCaptor =
        ArgumentCaptor.forClass(FailSettlementBatchCommand.class);
    verify(settlementBatchUseCase).failBatch(commandCaptor.capture());
    assertThat(commandCaptor.getValue().batchId()).isEqualTo(batchId);
    assertThat(commandCaptor.getValue().reason())
        .contains("BusinessException")
        .contains("Reconciliation gate blocked settlementMonth=202602");
  }
}
