package com.openat.product.domain.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductSearchProjectionRefreshTargetRepository {

  int enqueueBySellerId(UUID sellerId);

  int enqueueByCategoryId(UUID categoryId);

  List<UUID> findNextProductIdsForUpdate(int limit);

  int deleteAllByProductIds(Collection<UUID> productIds);
}
