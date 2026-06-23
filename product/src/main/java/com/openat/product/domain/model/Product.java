package com.openat.product.domain.model;

import com.openat.category.domain.model.Category;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
    name = "products",
    indexes = {
      @Index(name = "idx_products_seller_id", columnList = "seller_id"),
      @Index(name = "idx_products_category_id", columnList = "category_id")
    })
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.TIME)
  @Column(nullable = false, updatable = false, comment = "상품 id")
  private UUID id;

  @Column(name = "seller_id", nullable = false, updatable = false, comment = "판매자 ID(값 참조)")
  private UUID sellerId;

  @Column(nullable = false, length = 100, comment = "상품명")
  private String name;

  @Column(columnDefinition = "text", comment = "상품 상세 설명")
  private String description;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "category_id", nullable = false)
  private Category category;

  @Column(name = "price", comment = "판매 가격, null: 가격 미정")
  private Long price;

  @Column(name = "thumbnail_key", length = 512, comment = "썸네일 이미지 키")
  private String thumbnailKey;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false, comment = "생성 일시")
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false, comment = "수정 일시")
  private Instant updatedAt;

  @Builder(builderMethodName = "create")
  private Product(
      UUID sellerId,
      String name,
      String description,
      Category category,
      Long price,
      String thumbnailKey) {
    this.sellerId = sellerId;
    this.name = name;
    this.description = description;
    this.category = category;
    this.price = price;
    this.thumbnailKey = thumbnailKey;
  }
}
