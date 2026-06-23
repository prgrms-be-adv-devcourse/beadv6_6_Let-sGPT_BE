package com.openat.category.infrastructure.persistence;

import com.openat.category.domain.model.Category;
import com.openat.category.domain.repository.CategoryRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CategoryRepositoryAdaptor implements CategoryRepository {

  private final CategoryJpaRepository categoryJpaRepository;

  @Override
  public Optional<Category> findById(UUID id) {
    return categoryJpaRepository.findById(id);
  }
}
