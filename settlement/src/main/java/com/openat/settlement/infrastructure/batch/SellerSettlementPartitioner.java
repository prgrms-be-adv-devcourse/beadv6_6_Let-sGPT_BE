package com.openat.settlement.infrastructure.batch;

import com.openat.settlement.domain.repository.SettlementOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 정산 대상 sellerId 목록을 partition 단위로 나눕니다.
 */
@Component
@StepScope
@RequiredArgsConstructor
public class SellerSettlementPartitioner implements Partitioner {

    private final SettlementOrderRepository settlementOrderRepository;

    @Value("#{jobParameters['settlementMonth']}")
    private String settlementMonth;

    /**
     * partition 하나에 들어갈 sellerId 개수입니다.
     *
     * application.yml:
     * settlement.batch.seller-partition-size
     */
    @Value("${settlement.batch.seller-partition-size}")
    private int sellerPartitionSize;

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        List<UUID> sellerIds = settlementOrderRepository.findReadySellerIds(settlementMonth);

        Map<String, ExecutionContext> result = new LinkedHashMap<>();
        int partitionNumber = 0;

        for (int start = 0; start < sellerIds.size(); start += sellerPartitionSize) {
            int end = Math.min(start + sellerPartitionSize, sellerIds.size());

            String sellerIdsText = sellerIds.subList(start, end)
                    .stream()
                    .map(UUID::toString)
                    .collect(Collectors.joining(","));

            ExecutionContext context = new ExecutionContext();
            context.putString("sellerIds", sellerIdsText);

            result.put("sellerPartition-" + partitionNumber, context);
            partitionNumber++;
        }

        return result;
    }
}
