package com.openat.seller.application.service;

import com.openat.seller.application.usecase.SellerStoreCommandUseCase;
import com.openat.seller.domain.model.SellerStore;
import com.openat.seller.domain.repository.SellerStoreRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class SellerStoreCommandService implements SellerStoreCommandUseCase {

  private final SellerStoreRepository sellerStoreRepository;

  @Override
  public void upsert(UUID sellerInfoId, String storeName) {
    SellerStore sellerStore = sellerStoreRepository.findById(sellerInfoId).orElse(null);
    if (sellerStore == null) {
      sellerStoreRepository.save(
          SellerStore.project().sellerInfoId(sellerInfoId).storeName(storeName).build());
      return;
    }
    sellerStore.changeStoreName(storeName);
  }
}
