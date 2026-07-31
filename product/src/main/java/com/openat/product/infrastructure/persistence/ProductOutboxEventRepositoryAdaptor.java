package com.openat.product.infrastructure.persistence;

import com.openat.product.domain.model.ProductOutboxEvent;
import com.openat.product.domain.model.ProductOutboxEventStatus;
import com.openat.product.domain.repository.ProductOutboxEventRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ProductOutboxEventRepositoryAdaptor implements ProductOutboxEventRepository {

  private final ProductOutboxEventJpaRepository jpaRepository;

  @Override
  public ProductOutboxEvent save(ProductOutboxEvent event) {
    return jpaRepository.save(event);
  }

  @Override
  @Transactional
  public List<ProductOutboxEvent> claimPending(
      int limit, Instant claimedAt, Instant staleBefore) {
    jpaRepository.releaseStale(
        ProductOutboxEventStatus.PROCESSING,
        ProductOutboxEventStatus.PENDING,
        staleBefore);
    List<ProductOutboxEvent> events = jpaRepository.findClaimable(limit);
    events.forEach(event -> event.markProcessing(claimedAt));
    jpaRepository.flush();
    return List.copyOf(events);
  }

  @Override
  @Transactional
  public int markPublished(Collection<UUID> ids, Instant publishedAt) {
    if (ids.isEmpty()) {
      return 0;
    }
    return jpaRepository.markPublished(
        ids,
        ProductOutboxEventStatus.PROCESSING,
        ProductOutboxEventStatus.PUBLISHED,
        publishedAt);
  }

  @Override
  @Transactional
  public int release(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return 0;
    }
    return jpaRepository.release(
        ids, ProductOutboxEventStatus.PROCESSING, ProductOutboxEventStatus.PENDING);
  }

  @Override
  public long countByStatus(ProductOutboxEventStatus status) {
    return jpaRepository.countByStatus(status);
  }
}
