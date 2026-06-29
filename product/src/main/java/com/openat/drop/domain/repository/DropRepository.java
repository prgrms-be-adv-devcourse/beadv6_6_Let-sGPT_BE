package com.openat.drop.domain.repository;

import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DropRepository {
  Drop save(Drop drop);

  void delete(Drop drop);

  Optional<Drop> findById(UUID id);

  Optional<Drop> findWithProductById(UUID id);

  List<Drop> findAllByStatus(DropStatus status);

  List<Drop> findAllByProductId(UUID productId);

  Page<Drop> search(DropSearchCondition condition, Instant now, Pageable pageable);
}
