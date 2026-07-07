package com.openat.settlement.application.usecase;

import com.openat.settlement.application.dto.FindSettlementBatchResultsQuery;
import com.openat.settlement.application.dto.FindSellerSettlementsQuery;
import com.openat.settlement.application.dto.FindSettlementOrdersQuery;
import com.openat.settlement.application.dto.SellerSettlementSummary;
import com.openat.settlement.application.dto.SettlementBatchResultSummary;
import com.openat.settlement.application.dto.SettlementOrderSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SettlementQueryUseCase {

    Page<SettlementOrderSummary> findSettlementOrders(
            FindSettlementOrdersQuery query,
            Pageable pageable
    );

    Page<SellerSettlementSummary> findSellerSettlements(
            FindSellerSettlementsQuery query,
            Pageable pageable
    );

    Page<SettlementBatchResultSummary> findSettlementBatchResults(
            FindSettlementBatchResultsQuery query,
            Pageable pageable
    );
}
