package com.openat.drop.infrastructure.persistence;

import com.openat.drop.domain.model.StockHistory;
import com.openat.drop.domain.repository.BuyerPurchase;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockHistoryJpaRepository extends JpaRepository<StockHistory, UUID> {

  @Query("SELECT COALESCE(SUM(h.quantityDelta), 0) FROM StockHistory h WHERE h.dropId = :dropId")
  long sumQuantityDeltaByDropId(@Param("dropId") UUID dropId);

  @Query(
      "SELECT new com.openat.drop.domain.repository.BuyerPurchase(h.buyerId, SUM(-h.quantityDelta)) "
          + "FROM StockHistory h WHERE h.dropId = :dropId GROUP BY h.buyerId")
  List<BuyerPurchase> sumNetQuantityByBuyer(@Param("dropId") UUID dropId);
}
