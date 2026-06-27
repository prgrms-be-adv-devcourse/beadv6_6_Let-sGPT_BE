package com.openat.drop.domain.repository;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record DropCacheState(
    UUID dropId,
    long remaining,
    Instant openAt,
    Instant closeAt,
    Integer limitPerUser,
    Map<UUID, Long> buyers) {}
