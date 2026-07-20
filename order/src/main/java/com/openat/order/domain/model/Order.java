package com.openat.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Getter
@Table(
    name = "orders",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_orders_order_number", columnNames = "order_number"),
      @UniqueConstraint(
          name = "uk_orders_member_id_idempotency_key",
          columnNames = {"member_id", "idempotency_key"})
    },
    indexes = {
      @Index(name = "idx_orders_member_id", columnList = "member_id"),
      @Index(name = "idx_orders_drop_id", columnList = "drop_id"),
      @Index(name = "idx_orders_status", columnList = "status"),
      @Index(name = "idx_orders_status_completed_at", columnList = "status, completed_at"),
      @Index(name = "idx_orders_saga_id", columnList = "saga_id")
    })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

  private static final long PAYMENT_TTL_SECONDS = 10 * 60;

  @Id
  @UuidGenerator(style = UuidGenerator.Style.TIME)
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "order_number", nullable = false, length = 30, updatable = false)
  private String orderNumber;

  @Column(name = "member_id", nullable = false, updatable = false)
  private UUID memberId;

  @Column(name = "drop_id", nullable = false, updatable = false)
  private UUID dropId;

  @Column(name = "product_id", nullable = false, updatable = false)
  private UUID productId;

  @Column(name = "seller_id", nullable = false, updatable = false)
  private UUID sellerId;

  @Column(name = "product_name", nullable = false)
  private String productName;

  @Column(nullable = false)
  private int quantity;

  @Column(name = "unit_price", nullable = false)
  private long unitPrice;

  @Column(name = "total_price", nullable = false)
  private long totalPrice;

  @Column(name = "payment_id")
  private UUID paymentId;

  @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
  private String idempotencyKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private OrderStatus status;

  @Column(name = "payment_expires_at")
  private Instant paymentExpiresAt;

  @Column(name = "payment_status_check_failure_count")
  private Integer paymentStatusCheckFailureCount;

  @Column(name = "payment_status_check_failed_at")
  private Instant paymentStatusCheckFailedAt;

  @Column(name = "next_payment_status_check_at")
  private Instant nextPaymentStatusCheckAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "fail_code", length = 50)
  private OrderFailCode failCode;

  @Column(name = "fail_message")
  private String failMessage;

  @Column(name = "saga_id", length = 64)
  private String sagaId;

  @Version
  @Column(nullable = false)
  private long version;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "paid_at")
  private Instant paidAt;

  @Column(name = "completed_at")
  private Instant completedAt;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Column(name = "refunded_at")
  private Instant refundedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Builder(builderMethodName = "create")
  private Order(
      String orderNumber,
      UUID memberId,
      UUID dropId,
      UUID productId,
      UUID sellerId,
      String productName,
      int quantity,
      long unitPrice,
      String idempotencyKey,
      Instant now) {
    this.orderNumber = orderNumber;
    this.memberId = memberId;
    this.dropId = dropId;
    this.productId = productId;
    this.sellerId = sellerId;
    this.productName = productName;
    this.quantity = quantity;
    this.unitPrice = unitPrice;
    this.totalPrice = unitPrice * quantity;
    this.idempotencyKey = idempotencyKey;
    this.status = OrderStatus.PAYMENT_PENDING;
    this.paymentExpiresAt = now.plusSeconds(PAYMENT_TTL_SECONDS);
  }

  public boolean isOwnedBy(UUID requesterId) {
    return memberId.equals(requesterId);
  }

  public void assignSagaId(String sagaId) {
    this.sagaId = sagaId;
  }

  public void recordFailure(OrderFailCode failCode, String failMessage) {
    this.failCode = failCode;
    this.failMessage = failMessage;
  }

  public void clearFailure() {
    this.failCode = null;
    this.failMessage = null;
  }

  public boolean complete(UUID paymentId, Instant paidAt) {
    if (status != OrderStatus.PAYMENT_PENDING) {
      return false;
    }
    this.paymentId = paymentId;
    this.status = OrderStatus.COMPLETED;
    this.paidAt = paidAt;
    this.completedAt = paidAt;
    this.failCode = null;
    this.failMessage = null;
    resetPaymentStatusCheckFailures();
    return true;
  }

  public boolean fail(OrderFailCode failCode, String message, Instant failedAt) {
    if (status != OrderStatus.PAYMENT_PENDING) {
      return false;
    }
    this.status = OrderStatus.FAILED;
    this.failCode = failCode;
    this.failMessage = message;
    this.cancelledAt = failedAt;
    resetPaymentStatusCheckFailures();
    return true;
  }

  public int recordPaymentStatusCheckFailure(Instant failedAt) {
    if (paymentStatusCheckFailedAt == null
        || !failedAt.isBefore(paymentStatusCheckFailedAt.plusSeconds(30))) {
      paymentStatusCheckFailureCount =
          paymentStatusCheckFailureCount == null ? 1 : paymentStatusCheckFailureCount + 1;
      paymentStatusCheckFailedAt = failedAt;
    }
    return paymentStatusCheckFailureCount == null ? 0 : paymentStatusCheckFailureCount;
  }

  public void resetPaymentStatusCheckFailures() {
    paymentStatusCheckFailureCount = 0;
    paymentStatusCheckFailedAt = null;
    nextPaymentStatusCheckAt = null;
  }

  public void deferPaymentStatusCheck(Instant nextCheckAt) {
    resetPaymentStatusCheckFailures();
    nextPaymentStatusCheckAt = nextCheckAt;
  }

  public boolean cancelPending(Instant cancelledAt) {
    if (status != OrderStatus.PAYMENT_PENDING) {
      return false;
    }
    this.status = OrderStatus.CANCELLED;
    this.cancelledAt = cancelledAt;
    return true;
  }

  public boolean requestRefund(Instant requestedAt) {
    if (status != OrderStatus.COMPLETED && status != OrderStatus.PAYMENT_PENDING) {
      return false;
    }
    this.status = OrderStatus.CANCEL_REQUESTED;
    this.cancelledAt = requestedAt;
    return true;
  }

  public boolean refund(Instant refundedAt) {
    if (status != OrderStatus.CANCEL_REQUESTED
        && status != OrderStatus.REFUND_PENDING
        && status != OrderStatus.REFUND_FAILED
        && status != OrderStatus.CANCELLED) {
      return false;
    }
    this.status = OrderStatus.REFUNDED;
    this.refundedAt = refundedAt;
    clearFailure();
    return true;
  }

  public boolean confirmRefund(Instant refundedAt) {
    if (status != OrderStatus.REFUND_FAILED && status != OrderStatus.CANCELLED) {
      return false;
    }
    this.status = OrderStatus.REFUNDED;
    this.refundedAt = refundedAt;
    clearFailure();
    return true;
  }

  public boolean failRefund(String reason) {
    if (status != OrderStatus.CANCEL_REQUESTED && status != OrderStatus.REFUND_PENDING) {
      return false;
    }
    this.status = OrderStatus.REFUND_FAILED;
    this.failCode = OrderFailCode.PG_ERROR;
    this.failMessage = reason;
    return true;
  }
}
