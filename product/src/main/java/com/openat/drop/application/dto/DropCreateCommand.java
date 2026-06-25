package com.openat.drop.application.dto;

import java.time.Instant;
import java.util.UUID;

public record DropCreateCommand(
    UUID sellerId,
    UUID productId,
    Long dropPrice,
    int totalQuantity,
    Integer limitPerUser,
    Instant openAt,
    Instant closeAt) {}
