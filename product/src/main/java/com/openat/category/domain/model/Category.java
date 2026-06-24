package com.openat.category.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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
    name = "categories",
    uniqueConstraints = @UniqueConstraint(name = "uk_categories_name", columnNames = "name"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

  @Id
  @UuidGenerator(style = UuidGenerator.Style.TIME)
  @Column(nullable = false, updatable = false, comment = "카테고리 id")
  private UUID id;

  @Column(nullable = false, length = 50, comment = "카테고리명")
  private String name;

  @CreationTimestamp
  @Column(name = "created_at", nullable = false, updatable = false, comment = "생성 일시")
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false, comment = "수정 일시")
  private Instant updatedAt;

  @Builder(builderMethodName = "create")
  private Category(String name) {
    this.name = name;
  }

  public void update(String name) {
    this.name = name;
  }
}
