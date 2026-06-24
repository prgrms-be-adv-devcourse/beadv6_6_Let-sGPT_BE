package com.openat.settlement.infrastructure.batch;

import com.openat.settlement.application.dto.ProcessSellerIdsCommand;
import com.openat.settlement.application.usecase.SellerSettlementWorkerUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * partition 하나에 포함된 sellerId 목록을 실제 처리합니다.
 */
@Component
@StepScope
@RequiredArgsConstructor
public class SellerSettlementWorkerTasklet implements Tasklet {

    private final SellerSettlementWorkerUseCase sellerSettlementWorkerUseCase;

    @Value("#{stepExecutionContext['sellerIds']}")
    private String sellerIdsText;

    @Value("#{jobParameters['settlementMonth']}")
    private String settlementMonth;

    @Value("#{jobExecutionContext['batchId']}")
    private String batchId;

    @Override
    public RepeatStatus execute(
            StepContribution contribution,
            ChunkContext chunkContext
    ) {
        List<UUID> sellerIds = Arrays.stream(sellerIdsText.split(","))
                .filter(value -> !value.isBlank())
                .map(UUID::fromString)
                .toList();

        sellerSettlementWorkerUseCase.processSellerIds(
                new ProcessSellerIdsCommand(
                        UUID.fromString(batchId),
                        settlementMonth,
                        sellerIds
                )
        );

        return RepeatStatus.FINISHED;
    }
}
