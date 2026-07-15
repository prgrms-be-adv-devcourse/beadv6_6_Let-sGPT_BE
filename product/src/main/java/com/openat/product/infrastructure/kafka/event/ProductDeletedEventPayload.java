package com.openat.product.infrastructure.kafka.event;

import java.time.Instant;
import java.util.UUID;

public record ProductDeletedEventPayload(UUID id, Instant deletedAt) {}
