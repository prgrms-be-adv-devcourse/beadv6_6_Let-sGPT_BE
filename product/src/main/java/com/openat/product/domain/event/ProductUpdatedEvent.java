package com.openat.product.domain.event;

import com.openat.product.domain.model.Product;

public record ProductUpdatedEvent(Product product) {}
