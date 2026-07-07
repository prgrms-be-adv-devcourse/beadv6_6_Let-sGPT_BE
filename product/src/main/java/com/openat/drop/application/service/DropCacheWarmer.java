package com.openat.drop.application.service;

import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.repository.BuyerPurchase;
import com.openat.drop.domain.repository.DropCacheRepository;
import com.openat.drop.domain.repository.DropCacheState;
import com.openat.drop.domain.repository.DropRepository;
import com.openat.drop.domain.repository.StockHistoryRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DropCacheWarmer {

  private final DropRepository dropRepository;
  private final StockHistoryRepository stockHistoryRepository;
  private final DropCacheRepository dropCacheRepository;

  public void warm(UUID dropId) {
    Drop drop = dropRepository.findById(dropId).orElse(null);
    if (drop == null || drop.getStatus() == DropStatus.CLOSE) {
      return;
    }

    long remaining =
        drop.getTotalQuantity() + stockHistoryRepository.sumQuantityDeltaByDropId(dropId);

    Map<UUID, Long> buyers = new HashMap<>();
    for (BuyerPurchase purchase : stockHistoryRepository.sumNetQuantityByBuyer(dropId)) {
      if (purchase.quantity() > 0) {
        buyers.put(purchase.buyerId(), purchase.quantity());
      }
    }

    dropCacheRepository.warm(
        new DropCacheState(
            dropId,
            remaining,
            drop.getOpenAt(),
            drop.getCloseAt(),
            drop.getLimitPerUser(),
            buyers));
  }
}
