package com.openat.search.product.application.dto;

import java.time.Instant;

public record ReIndexTestResult(
    Instant changedAfter,
    int receivedCount,
    int insertCount,
    int updateCount,
    int deleteCount,
    int indexedCount,
    Instant lastIndexedAt,
    boolean stateUpdated) {}
