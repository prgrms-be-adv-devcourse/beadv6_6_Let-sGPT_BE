package com.openat.product.domain.repository;

import com.openat.product.domain.model.Product;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductRepository {
  Product save(Product product);

  Optional<Product> findById(UUID id);

  Page<Product> search(ProductSearchCondition condition, Pageable pageable);

  List<Product> searchChangedAliveSince(Instant changedAfter);

  List<ProductTombstone> searchTombstonesSince(Instant changedAfter);

  void delete(Product product);
}
