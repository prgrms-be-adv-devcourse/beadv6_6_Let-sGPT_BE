package com.openat.recommendation.domain.model;

import java.time.Instant;
import java.util.UUID;

public record DropMeta(
    UUID dropId,
    UUID productId,
    String productName,
    String sellerName,
    long dropPrice,
    String thumbnailKey,
    UUID categoryId,
    Instant closeAt) {}
