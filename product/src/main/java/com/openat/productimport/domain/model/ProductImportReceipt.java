package com.openat.productimport.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@Table(
    name = "product_import_receipts",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_product_import_receipts_seller_external",
            columnNames = {"seller_id", "external_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImportReceipt {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "seller_id", nullable = false, updatable = false)
  private UUID sellerId;

  @Column(name = "external_id", nullable = false, updatable = false, length = 100)
  private String externalId;

  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Column(name = "drop_id", updatable = false)
  private UUID dropId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  private ProductImportReceipt(UUID sellerId, String externalId, UUID productId, UUID dropId) {
    this.id = UUID.randomUUID();
    this.sellerId = sellerId;
    this.externalId = externalId;
    this.productId = productId;
    this.dropId = dropId;
    this.createdAt = Instant.now();
  }

  public static ProductImportReceipt create(
      UUID sellerId, String externalId, UUID productId, UUID dropId) {
    return new ProductImportReceipt(sellerId, externalId, productId, dropId);
  }
}
