package com.openat.product.domain.repository;

import com.openat.product.domain.model.SellerStoreProjection;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SellerStoreProjectionRepository {
  Optional<SellerStoreProjection> findById(UUID sellerInfoId);

  Optional<SellerStoreProjection> findByIdForUpdate(UUID sellerInfoId);

  List<SellerStoreProjection> findAllById(Collection<UUID> sellerInfoIds);

  SellerStoreProjection save(SellerStoreProjection sellerStoreProjection);
}
