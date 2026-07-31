package com.openat.product.infrastructure.persistence;

import com.openat.product.domain.model.ProductOutboxEvent;
import com.openat.product.domain.model.ProductOutboxEventStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductOutboxEventJpaRepository
    extends JpaRepository<ProductOutboxEvent, UUID> {

  @Query(
      value =
          """
          SELECT event.*
            FROM product.product_outbox_events event
           WHERE event.status = 'PENDING'
             AND NOT EXISTS (
                   SELECT 1
                    FROM product.product_outbox_events predecessor
                    WHERE predecessor.aggregate_id = event.aggregate_id
                      AND predecessor.aggregate_sequence < event.aggregate_sequence
                      AND predecessor.status <> 'PUBLISHED'
                 )
           ORDER BY event.created_at, event.id
           LIMIT :limit
           FOR UPDATE SKIP LOCKED
          """,
      nativeQuery = true)
  List<ProductOutboxEvent> findClaimable(@Param("limit") int limit);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update ProductOutboxEvent event
         set event.status = :pending, event.claimedAt = null
       where event.status = :processing
         and event.claimedAt < :staleBefore
      """)
  int releaseStale(
      @Param("processing") ProductOutboxEventStatus processing,
      @Param("pending") ProductOutboxEventStatus pending,
      @Param("staleBefore") Instant staleBefore);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update ProductOutboxEvent event
         set event.status = :published,
             event.claimedAt = null,
             event.publishedAt = :publishedAt
       where event.id in :ids
         and event.status = :processing
      """)
  int markPublished(
      @Param("ids") Collection<UUID> ids,
      @Param("processing") ProductOutboxEventStatus processing,
      @Param("published") ProductOutboxEventStatus published,
      @Param("publishedAt") Instant publishedAt);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update ProductOutboxEvent event
         set event.status = :pending, event.claimedAt = null
       where event.id in :ids
         and event.status = :processing
      """)
  int release(
      @Param("ids") Collection<UUID> ids,
      @Param("processing") ProductOutboxEventStatus processing,
      @Param("pending") ProductOutboxEventStatus pending);

  long countByStatus(ProductOutboxEventStatus status);
}
