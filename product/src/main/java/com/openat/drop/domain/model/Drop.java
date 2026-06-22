package com.openat.drop.domain.model;

import com.openat.product.domain.model.Product;
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
    name = "drops",
    indexes = {
      @Index(name = "idx_drops_product_id", columnList = "product_id"),
      @Index(name = "idx_drops_status_open_at", columnList = "status, open_at")
    })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Drop {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.TIME)
  @Column(nullable = false, updatable = false, comment = "드롭 id")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "product_id", nullable = false, updatable = false)
  private Product product;

  @Column(name = "drop_price", nullable = false, comment = "판매 확정가")
  private Long dropPrice;

  @Column(name = "total_quantity", nullable = false, updatable = false, comment = "총 수량")
  private int totalQuantity;

  @Column(name = "limit_per_user", comment = "1인 구매 한도, null: 무제한")
  private Integer limitPerUser;

  @Column(name = "open_at", nullable = false, comment = "오픈 시각")
  private Instant openAt;

  @Column(name = "close_at", comment = "종료 시각, null: 매진까지")
  private Instant closeAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20, comment = "드롭 상태")
  private DropStatus status;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false, comment = "생성 일시")
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false, comment = "수정 일시")
  private Instant updatedAt;

  @Builder(builderMethodName = "schedule")
  private Drop(
      Product product,
      Long dropPrice,
      int totalQuantity,
      Integer limitPerUser,
      Instant openAt,
      Instant closeAt) {
    this.product = product;
    this.dropPrice = dropPrice;
    this.totalQuantity = totalQuantity;
    this.limitPerUser = limitPerUser;
    this.openAt = openAt;
    this.closeAt = closeAt;
    this.status = DropStatus.SCHEDULED;
  }
}
