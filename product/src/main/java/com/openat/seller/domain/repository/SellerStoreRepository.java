package com.openat.seller.domain.repository;

import com.openat.seller.domain.model.SellerStore;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SellerStoreRepository {
  Optional<SellerStore> findById(UUID sellerInfoId);

  List<SellerStore> findAllById(Collection<UUID> sellerInfoIds);

  SellerStore save(SellerStore sellerStore);
}
