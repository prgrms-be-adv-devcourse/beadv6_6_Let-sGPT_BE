package com.openat.product.infrastructure.persistence;

import com.openat.product.domain.model.Product;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductJpaRepository extends JpaRepository<Product, UUID> {

  // @SoftDelete(deleted_at IS NULL) 자동 필터는 JPQL/QueryDSL에만 적용되므로,
  // 삭제된 행을 조회하려면 네이티브 SQL로 우회한다(변경 피드 삭제 tombstone 전용).
  @Query(
      value =
          """
          SELECT p.id, p.deleted_at
            FROM product.products p
           WHERE p.deleted_at IS NOT NULL
             AND p.deleted_at > :changedAfter
          """,
      nativeQuery = true)
  List<Object[]> searchTombstonesSince(@Param("changedAfter") Instant changedAfter);
}
