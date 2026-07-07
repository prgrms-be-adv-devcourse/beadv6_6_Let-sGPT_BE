package com.openat.product.application.dto;

import java.util.List;
import java.util.UUID;

public record ProductUpdateCommand(
    UUID id,
    UUID sellerId,
    String name,
    String description,
    UUID categoryId,
    Long price,
    String thumbnailKey,
    List<String> imageKeys) {}
