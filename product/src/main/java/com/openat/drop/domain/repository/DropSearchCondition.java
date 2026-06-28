package com.openat.drop.domain.repository;

import com.openat.drop.domain.model.DropStatus;
import java.util.UUID;

public record DropSearchCondition(
    DropStatus status, UUID categoryId, String keyword, UUID sellerId) {}
