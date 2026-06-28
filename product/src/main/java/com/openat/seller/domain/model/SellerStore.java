package com.openat.seller.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Getter
@Table(name = "seller_stores")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SellerStore {

  @Id
  @Column(
      name = "seller_info_id",
      nullable = false,
      updatable = false,
      comment = "스토어 식별자(member SellerInfo.id 값 참조)")
  private UUID sellerInfoId;

  @Column(nullable = false, length = 255, comment = "스토어 표시명(member SellerInfo.storeName 투영)")
  private String storeName;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false, comment = "투영 생성 일시")
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false, comment = "투영 갱신 일시")
  private Instant updatedAt;

  @Builder(builderMethodName = "project")
  private SellerStore(UUID sellerInfoId, String storeName) {
    this.sellerInfoId = sellerInfoId;
    this.storeName = storeName;
  }

  public void changeStoreName(String storeName) {
    this.storeName = storeName;
  }
}
