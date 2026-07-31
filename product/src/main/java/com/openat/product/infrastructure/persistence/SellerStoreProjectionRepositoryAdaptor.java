package com.openat.product.infrastructure.persistence;

import com.openat.product.domain.model.SellerStoreProjection;
import com.openat.product.domain.repository.SellerStoreProjectionRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SellerStoreProjectionRepositoryAdaptor implements SellerStoreProjectionRepository {

  private final SellerStoreProjectionJpaRepository sellerStoreProjectionJpaRepository;

  @Override
  public Optional<SellerStoreProjection> findById(UUID sellerInfoId) {
    return sellerStoreProjectionJpaRepository.findById(sellerInfoId);
  }

  @Override
  public Optional<SellerStoreProjection> findByIdForUpdate(UUID sellerInfoId) {
    return sellerStoreProjectionJpaRepository.findByIdForUpdate(sellerInfoId);
  }

  @Override
  public List<SellerStoreProjection> findAllById(Collection<UUID> sellerInfoIds) {
    return sellerStoreProjectionJpaRepository.findAllById(sellerInfoIds);
  }

  @Override
  public SellerStoreProjection save(SellerStoreProjection sellerStoreProjection) {
    return sellerStoreProjectionJpaRepository.save(sellerStoreProjection);
  }
}
