package com.openat.product.infrastructure.persistence;

import com.openat.product.domain.model.Product;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductJpaRepository extends JpaRepository<Product, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select product from Product product where product.id = :id")
  java.util.Optional<Product> findByIdForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select product
        from Product product
        left join fetch product.category
       where product.id in :ids
       order by product.id
      """)
  List<Product> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);

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
