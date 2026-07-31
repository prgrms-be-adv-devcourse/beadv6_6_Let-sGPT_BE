package com.openat.product.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
    name = "product_search_projection_refresh_targets",
    indexes =
        @Index(
            name = "idx_product_search_refresh_enqueued",
            columnList = "enqueued_at, product_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSearchProjectionRefreshTarget {

  @Id
  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Column(name = "enqueued_at", nullable = false, updatable = false)
  private Instant enqueuedAt;
}
