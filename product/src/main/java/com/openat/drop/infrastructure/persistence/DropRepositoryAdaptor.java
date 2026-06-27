package com.openat.drop.infrastructure.persistence;

import com.openat.drop.domain.model.Drop;
import com.openat.drop.domain.model.DropStatus;
import com.openat.drop.domain.repository.DropRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class DropRepositoryAdaptor implements DropRepository {

  private final DropJpaRepository dropJpaRepository;

  @Override
  public Drop save(Drop drop) {
    return dropJpaRepository.save(drop);
  }

  @Override
  public void delete(Drop drop) {
    dropJpaRepository.delete(drop);
  }

  @Override
  public Optional<Drop> findById(UUID id) {
    return dropJpaRepository.findById(id);
  }

  @Override
  public List<Drop> findAllByStatus(DropStatus status) {
    return dropJpaRepository.findAllByStatus(status);
  }

  @Override
  public List<Drop> findAllByProductId(UUID productId) {
    return dropJpaRepository.findAllByProduct_Id(productId);
  }
}
