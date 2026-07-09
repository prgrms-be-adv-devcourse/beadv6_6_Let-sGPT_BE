package com.openat.search.product.domain.repository;

import com.openat.search.product.domain.model.Product;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductRepository {
  Optional<Product> findById(UUID id);

  Page<Product> search(ProductSearchCondition condition, Pageable pageable);
}
