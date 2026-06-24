package com.openat.settlement.application.service;

import com.openat.settlement.application.dto.FindSettlementBatchResultsQuery;
import com.openat.settlement.application.dto.FindSellerSettlementsQuery;
import com.openat.settlement.application.dto.FindSettlementOrdersQuery;
import com.openat.settlement.application.dto.SellerSettlementSummary;
import com.openat.settlement.application.dto.SettlementBatchResultSummary;
import com.openat.settlement.application.dto.SettlementOrderSummary;
import com.openat.settlement.application.usecase.SettlementQueryUseCase;
import com.openat.settlement.domain.repository.SellerSettlementRepository;
import com.openat.settlement.domain.repository.SettlementBatchRepository;
import com.openat.settlement.domain.repository.SettlementOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SettlementQueryService implements SettlementQueryUseCase {

    private final SettlementOrderRepository settlementOrderRepository;
    private final SellerSettlementRepository sellerSettlementRepository;
    private final SettlementBatchRepository settlementBatchRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<SettlementOrderSummary> findSettlementOrders(
            FindSettlementOrdersQuery query,
            Pageable pageable
    ) {
        return settlementOrderRepository.findAllByConditions(
                        query.settlementMonth(),
                        query.status(),
                        query.sellerId(),
                        query.orderId(),
                        pageable
                )
                .map(SettlementOrderSummary::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SellerSettlementSummary> findSellerSettlements(
            FindSellerSettlementsQuery query,
            Pageable pageable
    ) {
        return sellerSettlementRepository.findAllByConditions(
                        query.settlementMonth(),
                        query.sellerId(),
                        query.status(),
                        pageable
                )
                .map(SellerSettlementSummary::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SettlementBatchResultSummary> findSettlementBatchResults(
            FindSettlementBatchResultsQuery query,
            Pageable pageable
    ) {
        return settlementBatchRepository.findAllByConditions(
                        query.settlementMonth(),
                        query.status(),
                        pageable
                )
                .map(SettlementBatchResultSummary::from);
    }
}
