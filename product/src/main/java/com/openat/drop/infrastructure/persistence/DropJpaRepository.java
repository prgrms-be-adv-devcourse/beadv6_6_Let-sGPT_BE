package com.openat.drop.infrastructure.persistence;

import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DropJpaRepository extends JpaRepository<Drop, UUID> {

  @Query("select d from Drop d join fetch d.product where d.status = :status")
  List<Drop> findAllByStatus(@Param("status") DropStatus status);

  List<Drop> findAllByProduct_Id(UUID productId);

  @EntityGraph(attributePaths = "product")
  Optional<Drop> findWithProductById(UUID id);
}
