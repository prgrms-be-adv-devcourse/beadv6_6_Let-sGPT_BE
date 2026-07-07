package com.openat.product.domain.event;

import java.util.UUID;

public record ProductDeletedEvent(UUID productId) {}
