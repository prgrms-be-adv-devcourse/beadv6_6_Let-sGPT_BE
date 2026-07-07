package com.openat.seller.application.service;

import com.openat.seller.application.usecase.SellerStoreQueryUseCase;
import com.openat.seller.domain.model.SellerStore;
import com.openat.seller.domain.repository.SellerStoreRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerStoreQueryService implements SellerStoreQueryUseCase {

  private final SellerStoreRepository sellerStoreRepository;

  @Override
  public Map<UUID, String> findStoreNames(Collection<UUID> sellerInfoIds) {
    if (sellerInfoIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, String> storeNames = new HashMap<>();
    for (SellerStore sellerStore : sellerStoreRepository.findAllById(sellerInfoIds)) {
      storeNames.put(sellerStore.getSellerInfoId(), sellerStore.getStoreName());
    }
    return storeNames;
  }
}
