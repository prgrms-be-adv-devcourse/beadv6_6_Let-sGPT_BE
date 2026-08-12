package com.openat.drop.infrastructure.persistence;

import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DropJpaRepository extends JpaRepository<Drop, UUID> {

  @Query("select d from Drop d join fetch d.product where d.status = :status")
  List<Drop> findAllByStatus(@Param("status") DropStatus status);

  List<Drop> findAllByProduct_Id(UUID productId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from Drop d where d.id = :id")
  Optional<Drop> findByIdForUpdate(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select d from Drop d where d.product.id = :productId order by d.id")
  List<Drop> findAllByProductIdForUpdate(@Param("productId") UUID productId);

  @EntityGraph(attributePaths = "product")
  Optional<Drop> findWithProductById(UUID id);
}
