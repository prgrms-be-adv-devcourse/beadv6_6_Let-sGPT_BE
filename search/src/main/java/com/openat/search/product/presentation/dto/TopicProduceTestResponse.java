package com.openat.search.product.presentation.dto;

public record TopicProduceTestResponse(
    String message,
    String productCreatedTopic,
    String productUpdatedTopic,
    String productDeletedTopic,
    int createdCount,
    int updatedCount,
    int deletedCount,
    int totalCount) {}
