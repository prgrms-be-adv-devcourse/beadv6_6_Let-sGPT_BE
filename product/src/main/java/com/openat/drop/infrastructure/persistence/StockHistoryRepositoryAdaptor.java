package com.openat.drop.infrastructure.persistence;

import com.openat.drop.domain.model.StockHistory;
import com.openat.drop.domain.repository.BuyerPurchase;
import com.openat.drop.domain.repository.StockHistoryRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StockHistoryRepositoryAdaptor implements StockHistoryRepository {

  private final StockHistoryJpaRepository stockHistoryJpaRepository;

  @Override
  public StockHistory save(StockHistory stockHistory) {
    return stockHistoryJpaRepository.save(stockHistory);
  }

  @Override
  public long sumQuantityDeltaByDropId(UUID dropId) {
    return stockHistoryJpaRepository.sumQuantityDeltaByDropId(dropId);
  }

  @Override
  public List<BuyerPurchase> sumNetQuantityByBuyer(UUID dropId) {
    return stockHistoryJpaRepository.sumNetQuantityByBuyer(dropId);
  }
}
