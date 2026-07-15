package com.openat.product.domain.repository;

import java.time.Instant;
import java.util.UUID;

public record ProductTombstone(UUID id, Instant deletedAt) {}
