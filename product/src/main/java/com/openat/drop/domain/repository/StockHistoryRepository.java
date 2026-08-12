package com.openat.drop.domain.repository;

import com.openat.drop.domain.model.StockChangeType;
import com.openat.drop.domain.model.StockHistory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockHistoryRepository {
  StockHistory save(StockHistory stockHistory);

  Optional<StockHistory> findByOrderIdAndChangeType(UUID orderId, StockChangeType changeType);

  Optional<StockHistory> findByOrderIdAndChangeTypeForUpdate(
      UUID orderId, StockChangeType changeType);

  long sumQuantityDeltaByDropId(UUID dropId);

  List<BuyerPurchase> sumNetQuantityByBuyer(UUID dropId);
}
