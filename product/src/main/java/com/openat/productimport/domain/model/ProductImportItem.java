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
    name = "product_import_items",
    indexes = @Index(name = "idx_product_import_items_job", columnList = "job_id, row_number"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImportItem {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "job_id", nullable = false, updatable = false)
  private UUID jobId;

  @Column(name = "row_number", nullable = false, updatable = false)
  private int rowNumber;

  @Column(name = "external_id", length = 100)
  private String externalId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private ProductImportItemStatus status;

  @Column(name = "product_id")
  private UUID productId;

  @Column(name = "drop_id")
  private UUID dropId;

  @Column(name = "result_message", length = 2000)
  private String message;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  private ProductImportItem(
      UUID jobId,
      int rowNumber,
      String externalId,
      ProductImportItemStatus status,
      UUID productId,
      UUID dropId,
      String message) {
    this.id = UUID.randomUUID();
    this.jobId = jobId;
    this.rowNumber = rowNumber;
    this.externalId = externalId;
    this.status = status;
    this.productId = productId;
    this.dropId = dropId;
    this.message = truncate(message, 2000);
    this.createdAt = Instant.now();
  }

  public static ProductImportItem create(
      UUID jobId,
      int rowNumber,
      String externalId,
      ProductImportItemStatus status,
      UUID productId,
      UUID dropId,
      String message) {
    return new ProductImportItem(jobId, rowNumber, externalId, status, productId, dropId, message);
  }

  private static String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength);
  }
}
