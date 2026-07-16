package com.openat.productimport.presentation.dto;

import com.openat.productimport.domain.model.ProductImportJob;
import com.openat.productimport.domain.model.ProductImportJobStatus;
import com.openat.productimport.domain.model.ProductImportSourceType;
import java.time.Instant;
import java.util.UUID;

public record ProductImportJobResponse(
    UUID jobId,
    ProductImportJobStatus status,
    ProductImportSourceType sourceType,
    String location,
    boolean dryRun,
    int totalRows,
    int processedRows,
    int successCount,
    int failureCount,
    int skippedCount,
    String fatalError,
    Instant createdAt,
    Instant startedAt,
    Instant completedAt) {

  public static ProductImportJobResponse from(ProductImportJob job) {
    return new ProductImportJobResponse(
        job.getId(),
        job.getStatus(),
        job.getSourceType(),
        job.getLocation(),
        job.isDryRun(),
        job.getTotalRows(),
        job.getSuccessCount() + job.getFailureCount() + job.getSkippedCount(),
        job.getSuccessCount(),
        job.getFailureCount(),
        job.getSkippedCount(),
        job.getFatalError(),
        job.getCreatedAt(),
        job.getStartedAt(),
        job.getCompletedAt());
  }
}
