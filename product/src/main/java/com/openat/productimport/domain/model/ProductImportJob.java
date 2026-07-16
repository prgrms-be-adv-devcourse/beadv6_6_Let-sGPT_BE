package com.openat.productimport.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
    name = "product_import_jobs",
    indexes = @Index(name = "idx_product_import_jobs_seller", columnList = "seller_id, created_at"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImportJob {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "seller_id", nullable = false, updatable = false)
  private UUID sellerId;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, updatable = false, length = 20)
  private ProductImportSourceType sourceType;

  @Column(nullable = false, updatable = false, length = 2048)
  private String location;

  @Column(name = "dry_run", nullable = false, updatable = false)
  private boolean dryRun;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 40)
  private ProductImportJobStatus status;

  @Column(name = "total_rows", nullable = false)
  private int totalRows;

  @Column(name = "success_count", nullable = false)
  private int successCount;

  @Column(name = "failure_count", nullable = false)
  private int failureCount;

  @Column(name = "skipped_count", nullable = false)
  private int skippedCount;

  @Column(name = "fatal_error", length = 2000)
  private String fatalError;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  private ProductImportJob(
      UUID sellerId, ProductImportSourceType sourceType, String location, boolean dryRun) {
    this.id = UUID.randomUUID();
    this.sellerId = sellerId;
    this.sourceType = sourceType;
    this.location = location;
    this.dryRun = dryRun;
    this.status = ProductImportJobStatus.PENDING;
    this.createdAt = Instant.now();
  }

  public static ProductImportJob create(
      UUID sellerId, ProductImportSourceType sourceType, String location, boolean dryRun) {
    return new ProductImportJob(sellerId, sourceType, location, dryRun);
  }

  public void start() {
    status = ProductImportJobStatus.RUNNING;
    startedAt = Instant.now();
  }

  public void setTotalRows(int totalRows) {
    this.totalRows = totalRows;
  }

  public void record(ProductImportItemStatus itemStatus) {
    switch (itemStatus) {
      case IMPORTED, VALIDATED -> successCount++;
      case SKIPPED -> skippedCount++;
      case FAILED -> failureCount++;
    }
  }

  public void complete() {
    status =
        failureCount == 0
            ? ProductImportJobStatus.COMPLETED
            : ProductImportJobStatus.COMPLETED_WITH_ERRORS;
    completedAt = Instant.now();
  }

  public void fail(String message) {
    status = ProductImportJobStatus.FAILED;
    fatalError = truncate(message, 2000);
    completedAt = Instant.now();
  }

  private static String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }
}
