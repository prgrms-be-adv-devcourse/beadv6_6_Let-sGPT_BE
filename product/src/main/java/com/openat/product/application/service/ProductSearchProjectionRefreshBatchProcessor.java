package com.openat.product.application.service;

import com.openat.product.domain.event.ProductUpdatedEvent;
import com.openat.product.domain.model.Product;
import com.openat.product.domain.repository.ProductRepository;
import com.openat.product.domain.repository.ProductSearchProjectionRefreshTargetRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProductSearchProjectionRefreshBatchProcessor {

  private final ProductSearchProjectionRefreshTargetRepository refreshTargetRepository;
  private final ProductRepository productRepository;
  private final ApplicationEventPublisher eventPublisher;
  private final int batchSize;

  public ProductSearchProjectionRefreshBatchProcessor(
      ProductSearchProjectionRefreshTargetRepository refreshTargetRepository,
      ProductRepository productRepository,
      ApplicationEventPublisher eventPublisher,
      @Value("${product.search-projection-refresh.batch-size:100}") int batchSize) {
    if (batchSize <= 0) {
      throw new IllegalArgumentException(
          "product search projection refresh batchSize must be positive");
    }
    this.refreshTargetRepository = refreshTargetRepository;
    this.productRepository = productRepository;
    this.eventPublisher = eventPublisher;
    this.batchSize = batchSize;
  }

  @Transactional
  public int processNextBatch() {
    List<UUID> targetIds =
        refreshTargetRepository.findNextProductIdsForUpdate(batchSize);
    if (targetIds.isEmpty()) {
      return 0;
    }

    List<Product> products = productRepository.findAllByIdForUpdate(targetIds);
    for (Product product : products) {
      product.refreshSearchProjection();
      eventPublisher.publishEvent(new ProductUpdatedEvent(product));
    }
    refreshTargetRepository.deleteAllByProductIds(targetIds);
    return targetIds.size();
  }
}
