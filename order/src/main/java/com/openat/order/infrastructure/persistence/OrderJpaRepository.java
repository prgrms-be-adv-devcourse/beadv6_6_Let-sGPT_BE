package com.openat.order.infrastructure.persistence;

import com.openat.order.domain.model.Order;
import com.openat.order.domain.model.OrderStatus;
import com.openat.order.domain.model.PurchaseSignal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderJpaRepository extends JpaRepository<Order, UUID> {

  Optional<Order> findByMemberIdAndIdempotencyKey(UUID memberId, String idempotencyKey);

  Page<Order> findByMemberId(UUID memberId, Pageable pageable);

  Page<Order> findByMemberIdAndStatus(UUID memberId, OrderStatus status, Pageable pageable);

  @Query(
      """
            SELECT new com.openat.order.domain.model.PurchaseSignal(
                o.productId,
                COUNT(o),
                CAST(SUM(o.quantity) AS long),
                MAX(o.createdAt)
            )
            FROM Order o
            WHERE o.memberId = :memberId
              AND o.status = :status
            GROUP BY o.productId
            ORDER BY MAX(o.createdAt) DESC
            """)
  List<PurchaseSignal> findPurchaseSignals(
      @Param("memberId") UUID memberId, @Param("status") OrderStatus status, Pageable pageable);

  @Query(
      """
            SELECT o.id
            FROM Order o
            WHERE o.status = :status
              AND o.paymentExpiresAt < :now
              AND (o.nextPaymentStatusCheckAt IS NULL OR o.nextPaymentStatusCheckAt <= :now)
            ORDER BY o.paymentExpiresAt ASC
            """)
  List<UUID> findExpiredPaymentPendingIds(
      @Param("status") OrderStatus status, @Param("now") java.time.Instant now, Pageable pageable);
}
