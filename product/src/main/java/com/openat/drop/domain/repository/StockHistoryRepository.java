package com.openat.drop.domain.repository;

import com.openat.drop.domain.model.StockHistory;
import java.util.List;
import java.util.UUID;

public interface StockHistoryRepository {
  StockHistory save(StockHistory stockHistory);

  long sumQuantityDeltaByDropId(UUID dropId);

  List<BuyerPurchase> sumNetQuantityByBuyer(UUID dropId);
}
