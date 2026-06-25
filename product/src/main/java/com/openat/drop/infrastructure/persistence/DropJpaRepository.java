package com.openat.drop.infrastructure.persistence;

import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DropJpaRepository extends JpaRepository<Drop, UUID> {
  List<Drop> findAllByStatus(DropStatus status);

  List<Drop> findAllByProduct_Id(UUID productId);
}
