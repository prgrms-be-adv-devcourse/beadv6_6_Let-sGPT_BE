package com.openat.recommendation.application.port.out;

import java.util.List;
import java.util.UUID;

public interface WishlistSignalClient {

  List<UUID> getWishlistProductIds(UUID memberId);
}
