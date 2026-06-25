package com.openat.product.domain.repository;

import java.util.UUID;

public record ProductSearchCondition(UUID categoryId, String keyword) {}
