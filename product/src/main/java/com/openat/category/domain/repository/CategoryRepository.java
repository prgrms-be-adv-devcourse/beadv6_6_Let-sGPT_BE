package com.openat.category.domain.repository;

import com.openat.category.domain.model.Category;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository {
  Optional<Category> findById(UUID id);
}
