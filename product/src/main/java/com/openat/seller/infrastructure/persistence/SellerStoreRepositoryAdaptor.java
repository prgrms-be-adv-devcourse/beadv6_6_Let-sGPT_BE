package com.openat.seller.infrastructure.persistence;

import com.openat.seller.domain.model.SellerStore;
import com.openat.seller.domain.repository.SellerStoreRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SellerStoreRepositoryAdaptor implements SellerStoreRepository {

  private final SellerStoreJpaRepository sellerStoreJpaRepository;

  @Override
  public Optional<SellerStore> findById(UUID sellerInfoId) {
    return sellerStoreJpaRepository.findById(sellerInfoId);
  }

  @Override
  public List<SellerStore> findAllById(Collection<UUID> sellerInfoIds) {
    return sellerStoreJpaRepository.findAllById(sellerInfoIds);
  }

  @Override
  public SellerStore save(SellerStore sellerStore) {
    return sellerStoreJpaRepository.save(sellerStore);
  }
}
