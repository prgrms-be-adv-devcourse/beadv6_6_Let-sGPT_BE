package com.openat.product.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.UUID;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Getter
@Table(
    name = "stock_histories",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_stock_histories_order_change",
            columnNames = {"order_id", "change_type"}),
    indexes = @Index(name = "idx_stock_histories_drop_id", columnList = "drop_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockHistory {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.TIME)
  @Column(nullable = false, updatable = false, comment = "재고 이력 id")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "drop_id", nullable = false, updatable = false)
  private Drop drop;

  @Column(name = "order_id", nullable = false, updatable = false, comment = "주문 ID(값 참조), 멱등 키")
  private UUID orderId;

  @Column(
      name = "buyer_id",
      nullable = false,
      updatable = false,
      comment = "구매자 ID(값 참조), 1인 한도 재구성용")
  private UUID buyerId;

  @Enumerated(EnumType.STRING)
  @Column(
      name = "change_type",
      nullable = false,
      length = 20,
      updatable = false,
      comment = "변경 유형: DEDUCT/ROLLBACK")
  private StockChangeType changeType;

  @Column(
      name = "quantity_delta",
      nullable = false,
      updatable = false,
      comment = "수량 증감, DEDUCT 음수 / ROLLBACK 양수")
  private int quantityDelta;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false, comment = "생성 일시")
  private Instant createdAt;

  @Builder(builderMethodName = "record")
  private StockHistory(
      Drop drop, UUID orderId, UUID buyerId, StockChangeType changeType, int quantity) {
    this.drop = drop;
    this.orderId = orderId;
    this.buyerId = buyerId;
    this.changeType = changeType;
    this.quantityDelta = (changeType == StockChangeType.DEDUCT) ? -quantity : quantity;
  }
}
