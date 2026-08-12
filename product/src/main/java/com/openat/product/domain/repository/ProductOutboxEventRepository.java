package com.openat.product.domain.repository;

import com.openat.product.domain.model.ProductOutboxEvent;
import com.openat.product.domain.model.ProductOutboxEventStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductOutboxEventRepository {

  ProductOutboxEvent save(ProductOutboxEvent event);

  List<ProductOutboxEvent> claimPending(int limit, Instant claimedAt, Instant staleBefore);

  int markPublished(Collection<UUID> ids, Instant publishedAt);

  int release(Collection<UUID> ids);

  long countByStatus(ProductOutboxEventStatus status);
}
