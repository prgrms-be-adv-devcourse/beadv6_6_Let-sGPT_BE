package com.openat.category.infrastructure.persistence;

import com.openat.category.domain.model.Category;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryJpaRepository extends JpaRepository<Category, UUID> {
  Optional<Category> findByName(String name);

  boolean existsByName(String name);
}
