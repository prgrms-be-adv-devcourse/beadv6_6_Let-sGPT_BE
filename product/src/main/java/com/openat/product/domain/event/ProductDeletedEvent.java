package com.openat.product.domain.event;

import java.time.Instant;
import java.util.UUID;

public record ProductDeletedEvent(UUID productId, Instant deletedAt) {}
