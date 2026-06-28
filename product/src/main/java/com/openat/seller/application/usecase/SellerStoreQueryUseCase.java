package com.openat.seller.application.usecase;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface SellerStoreQueryUseCase {
  Map<UUID, String> findStoreNames(Collection<UUID> sellerInfoIds);
}
