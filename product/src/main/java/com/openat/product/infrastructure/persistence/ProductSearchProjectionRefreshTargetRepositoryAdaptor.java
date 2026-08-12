package com.openat.product.infrastructure.persistence;

import com.openat.product.domain.repository.ProductSearchProjectionRefreshTargetRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class ProductSearchProjectionRefreshTargetRepositoryAdaptor
    implements ProductSearchProjectionRefreshTargetRepository {

  private final ProductSearchProjectionRefreshTargetJpaRepository jpaRepository;

  @Override
  @Transactional
  public int enqueueBySellerId(UUID sellerId) {
    return jpaRepository.enqueueBySellerId(sellerId);
  }

  @Override
  @Transactional
  public int enqueueByCategoryId(UUID categoryId) {
    return jpaRepository.enqueueByCategoryId(categoryId);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public List<UUID> findNextProductIdsForUpdate(int limit) {
    return List.copyOf(jpaRepository.findNextProductIdsForUpdate(limit));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public int deleteAllByProductIds(Collection<UUID> productIds) {
    if (productIds.isEmpty()) {
      return 0;
    }
    return jpaRepository.deleteAllByProductIds(productIds);
  }
}
