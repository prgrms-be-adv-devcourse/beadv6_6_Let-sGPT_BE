package com.openat.drop.domain.repository;

import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DropRepository {
  Drop save(Drop drop);

  void delete(Drop drop);

  Optional<Drop> findById(UUID id);

  List<Drop> findAllByStatus(DropStatus status);

  List<Drop> findAllByProductId(UUID productId);
}
