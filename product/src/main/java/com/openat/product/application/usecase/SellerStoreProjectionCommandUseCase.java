package com.openat.product.application.usecase;

import java.util.UUID;

public interface SellerStoreProjectionCommandUseCase {
  void upsert(UUID sellerInfoId, String storeName);
}
