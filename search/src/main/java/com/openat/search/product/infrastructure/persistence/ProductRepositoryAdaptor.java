package com.openat.search.product.infrastructure.persistence;

import com.openat.search.product.domain.model.Product;
import com.openat.search.product.domain.repository.ProductRepository;
import com.openat.search.product.domain.repository.ProductSearchCondition;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProductRepositoryAdaptor implements ProductRepository {

  private final ProductJpaRepository productJpaRepository;

  @Override
  public Optional<Product> findById(UUID id) {
    return productJpaRepository.findById(id);
  }

  @Override
  public Page<Product> search(ProductSearchCondition condition, Pageable pageable) {
    if (condition.sellerId() != null) {
      return productJpaRepository.findBySellerId(condition.sellerId(), pageable);
    }
    return productJpaRepository.findAll(pageable);
  }
}
