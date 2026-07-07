package com.openat.product.domain.repository;

import com.openat.product.domain.model.Product;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductRepository {
  Product save(Product product);

  Optional<Product> findById(UUID id);

  Page<Product> search(ProductSearchCondition condition, Pageable pageable);

  void delete(Product product);
}
