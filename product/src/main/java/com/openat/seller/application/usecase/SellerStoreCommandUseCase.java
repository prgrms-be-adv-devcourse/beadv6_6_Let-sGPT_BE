package com.openat.seller.application.usecase;

import java.util.UUID;

public interface SellerStoreCommandUseCase {
  void upsert(UUID sellerInfoId, String storeName);
}
