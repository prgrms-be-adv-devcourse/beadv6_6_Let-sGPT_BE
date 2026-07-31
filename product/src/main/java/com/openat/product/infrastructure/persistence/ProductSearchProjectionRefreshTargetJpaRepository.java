package com.openat.product.infrastructure.persistence;

import com.openat.product.domain.model.ProductSearchProjectionRefreshTarget;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductSearchProjectionRefreshTargetJpaRepository
    extends JpaRepository<ProductSearchProjectionRefreshTarget, UUID> {

  @Modifying
  @Query(
      value =
          """
          INSERT INTO product.product_search_projection_refresh_targets (product_id, enqueued_at)
          SELECT product.id, CURRENT_TIMESTAMP
            FROM product.products product
           WHERE product.seller_id = :sellerId
             AND product.deleted_at IS NULL
          ON CONFLICT (product_id)
          DO UPDATE SET enqueued_at = EXCLUDED.enqueued_at
          """,
      nativeQuery = true)
  int enqueueBySellerId(@Param("sellerId") UUID sellerId);

  @Modifying
  @Query(
      value =
          """
          INSERT INTO product.product_search_projection_refresh_targets (product_id, enqueued_at)
          SELECT product.id, CURRENT_TIMESTAMP
            FROM product.products product
           WHERE product.category_id = :categoryId
             AND product.deleted_at IS NULL
          ON CONFLICT (product_id)
          DO UPDATE SET enqueued_at = EXCLUDED.enqueued_at
          """,
      nativeQuery = true)
  int enqueueByCategoryId(@Param("categoryId") UUID categoryId);

  @Query(
      value =
          """
          SELECT target.product_id
            FROM product.product_search_projection_refresh_targets target
           ORDER BY target.enqueued_at, target.product_id
           LIMIT :limit
           FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<UUID> findNextProductIdsForUpdate(@Param("limit") int limit);

  @Modifying
  @Query(
      value =
          """
          DELETE FROM product.product_search_projection_refresh_targets
           WHERE product_id IN (:productIds)
          """,
      nativeQuery = true)
  int deleteAllByProductIds(@Param("productIds") Collection<UUID> productIds);
}
