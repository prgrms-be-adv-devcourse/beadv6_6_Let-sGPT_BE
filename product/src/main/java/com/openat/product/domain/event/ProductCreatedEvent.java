package com.openat.product.domain.event;

import com.openat.product.domain.model.Product;

public record ProductCreatedEvent(Product product) {}
