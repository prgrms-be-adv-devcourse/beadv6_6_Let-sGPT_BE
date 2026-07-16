package com.openat.category.infrastructure.persistence;

import com.openat.category.domain.model.Category;
import com.openat.category.domain.repository.CategoryRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class CategoryRepositoryAdaptor implements CategoryRepository {

  private final CategoryJpaRepository categoryJpaRepository;

  @Override
  public Optional<Category> findById(UUID id) {
    return categoryJpaRepository.findById(id);
  }

  @Override
  public Optional<Category> findByName(String name) {
    return categoryJpaRepository.findByName(name);
  }

  @Override
  public List<Category> findAll() {
    return categoryJpaRepository.findAll(Sort.by(Sort.Direction.ASC, "name"));
  }

  @Override
  public boolean existsByName(String name) {
    return categoryJpaRepository.existsByName(name);
  }

  @Override
  public Category save(Category category) {
    return categoryJpaRepository.save(category);
  }

  @Override
  public void delete(Category category) {
    categoryJpaRepository.delete(category);
  }
}
