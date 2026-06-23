package com.openat.settlement.application.service;

import com.openat.settlement.application.dto.SettleSellerCommand;
import com.openat.settlement.application.usecase.SellerSettlementUseCase;
import com.openat.settlement.domain.model.SellerSettlement;
import com.openat.settlement.domain.model.SettlementOrderAmount;
import com.openat.settlement.domain.model.SettlementOrderAggregate;
import com.openat.settlement.domain.repository.SellerSettlementRepository;
import com.openat.settlement.domain.repository.SettlementAdjustmentRepository;
import com.openat.settlement.domain.repository.SettlementOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * SellerSettlementUseCase 구현체입니다.
 *
 * 판매자 1명에 대한 실제 정산 계산을 담당합니다.
 */
@Service
@RequiredArgsConstructor
public class SellerSettlementProcessor implements SellerSettlementUseCase {

    private final SellerSettlementRepository sellerSettlementRepository;
    private final SettlementOrderRepository settlementOrderRepository;
    private final SettlementAdjustmentRepository settlementAdjustmentRepository;

    @Value("${settlement.batch.seller-order-chunk-size:1000}")
    private int sellerOrderChunkSize;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settleSeller(SettleSellerCommand command) {
        SellerSettlement existing = sellerSettlementRepository
                .findBySellerIdAndSettlementMonth(command.sellerId(), command.settlementMonth())
                .orElse(null);

        if (existing != null && existing.isCompleted()) {
            return;
        }

        SettlementOrderAggregate orderAggregate = aggregateReadyOrdersByChunk(command);

        long totalAdjustmentAmount =
                settlementAdjustmentRepository.sumReadyAdjustmentAmount(
                        command.sellerId(),
                        command.settlementMonth()
                );

        SellerSettlement sellerSettlement;

        if (existing == null) {
            sellerSettlement = SellerSettlement.create(
                    command.batchId(),
                    command.settlementMonth(),
                    command.sellerId(),
                    Math.toIntExact(orderAggregate.totalOrderCount()),
                    orderAggregate.totalPaidAmount(),
                    orderAggregate.totalFeeAmount(),
                    orderAggregate.totalRefundAmount(),
                    totalAdjustmentAmount
            );
        } else {
            sellerSettlement = existing;
            sellerSettlement.recalculate(
                    command.batchId(),
                    Math.toIntExact(orderAggregate.totalOrderCount()),
                    orderAggregate.totalPaidAmount(),
                    orderAggregate.totalFeeAmount(),
                    orderAggregate.totalRefundAmount(),
                    totalAdjustmentAmount
            );
        }

        sellerSettlement.complete();
        sellerSettlementRepository.save(sellerSettlement);

        settlementOrderRepository.completeReadyOrders(
                command.sellerId(),
                command.settlementMonth(),
                sellerSettlement.getId()
        );
        settlementAdjustmentRepository.applyReadyAdjustments(
                command.sellerId(),
                command.settlementMonth()
        );
    }

    private SettlementOrderAggregate aggregateReadyOrdersByChunk(SettleSellerCommand command) {
        int page = 0;
        int chunkSize = Math.max(1, sellerOrderChunkSize);
        long totalOrderCount = 0L;
        long totalPaidAmount = 0L;
        long totalFeeAmount = 0L;
        long totalRefundAmount = 0L;
        Slice<SettlementOrderAmount> chunk;

        do {
            chunk = settlementOrderRepository.findReadyOrderAmounts(
                    command.sellerId(),
                    command.settlementMonth(),
                    PageRequest.of(page, chunkSize)
            );

            for (SettlementOrderAmount amount : chunk.getContent()) {
                totalOrderCount++;
                totalPaidAmount += amount.paidAmount();
                totalFeeAmount += amount.feeAmount();
                totalRefundAmount += amount.refundAmount();
            }

            page++;
        } while (chunk.hasNext());

        return new SettlementOrderAggregate(
                totalOrderCount,
                totalPaidAmount,
                totalFeeAmount,
                totalRefundAmount
        );
    }
}
