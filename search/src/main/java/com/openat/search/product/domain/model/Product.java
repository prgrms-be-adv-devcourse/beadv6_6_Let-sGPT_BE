package com.openat.search.product.domain.model;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SoftDelete;
import org.hibernate.annotations.SoftDeleteType;

@Entity
@Getter
@Table(
    name = "products",
    schema = "product",
    indexes = {
      @Index(name = "idx_products_seller_id", columnList = "seller_id"),
      @Index(name = "idx_products_category_id", columnList = "category_id")
    })
@SoftDelete(strategy = SoftDeleteType.TIMESTAMP, columnName = "deleted_at")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

  @Id
  @Column(nullable = false, updatable = false)
  private UUID id;

  @Column(name = "seller_id", nullable = false, updatable = false)
  private UUID sellerId;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(columnDefinition = "text")
  private String description;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "category_id")
  private Category category;

  private Long price;

  @Column(name = "thumbnail_key", length = 512)
  private String thumbnailKey;

  @ElementCollection(fetch = FetchType.EAGER)
  @CollectionTable(
      name = "product_images",
      schema = "product",
      joinColumns = @JoinColumn(name = "product_id"),
      indexes = @Index(name = "idx_product_images_product_id", columnList = "product_id"))
  @OrderColumn(name = "image_order")
  @Column(name = "image_key", length = 512)
  private List<String> imageKeys;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;
}
