package com.openat.seller.infrastructure.kafka.event;

import java.util.UUID;

public record SellerStoreChangedEvent(UUID sellerInfoId, String storeName) {}
