package com.openat.product.infrastructure.kafka.event;

import java.util.UUID;

public record SellerStoreChangedEvent(UUID sellerInfoId, String storeName) {}
