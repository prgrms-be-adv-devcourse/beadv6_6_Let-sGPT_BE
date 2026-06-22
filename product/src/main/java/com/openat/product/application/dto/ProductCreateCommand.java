package com.openat.product.application.dto;

import java.util.UUID;

public record ProductCreateCommand(
    UUID sellerId,
    String name,
    String description,
    UUID categoryId,
    Long price,
    String thumbnailKey) {}
