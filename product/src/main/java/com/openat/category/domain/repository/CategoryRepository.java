package com.openat.category.domain.repository;

import com.openat.category.domain.model.Category;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository {
  Optional<Category> findById(UUID id);

  List<Category> findAll();

  boolean existsByName(String name);

  Category save(Category category);

  void delete(Category category);
}
